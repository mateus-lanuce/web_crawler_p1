# Detalhamento das Classes do Sistema Web Crawler

Este documento detalha cada classe implementada no projeto, suas funções internas e o motivo de suas escolhas arquiteturais. Todas as especificações propostas no arquivo `projeto.md` foram rigorosamente atendidas.

## 1. `com.crawler.dataserver.DataServer`
Responsável por simular a internet (Mock) servindo dados rapidamente sobre TCP.
* **Estruturas Internas**:
  * `Map<String, PageData> internetMock`: Usado para armazenar as páginas na memória. O `HashMap` foi escolhido por fornecer acesso extremamente rápido O(1) aos dados baseados na URL.
  * `class PageData`: Classe aninhada simples para agrupar o texto e os links de uma página, facilitando o transporte dentro da aplicação.
  * `class DataWorker (Runnable)`: Classe interna usada para tratar cada requisição de socket em uma thread separada. O DataServer pode receber conexões de centenas de threads de workers ao mesmo tempo sem bloquear o servidor.
* **Funções**:
  * `loadDataFromCsv(String filePath)`: Lê o arquivo `.csv`, remove caracteres indesejados (como o BOM do UTF-8 que pode causar bugs no Windows) e popula o `HashMap`.
  * `main()`: Inicia o `ServerSocket` e o `ExecutorService` (`CachedThreadPool`) para gerenciar as conexões recebidas dinamicamente.
* **Por que foram utilizadas**: A abordagem de ler um CSV inteiro para a memória (RAM) garante respostas na casa de sub-milissegundos via Sockets TCP, simulando de forma limpa o cenário exigido pelo projeto sem a complexidade de gerenciar um banco de dados externo.

## 2. `com.crawler.coordinator.Coordinator`
O "Master" do sistema. Gerencia a fila global de URLs e orquestra os processos, atuando como controlador de estado.
* **Estruturas Internas**:
  * `BlockingQueue<String> urlQueue`: Fila thread-safe (`LinkedBlockingQueue`). Ideal para ambientes onde múltiplas threads (`WorkerHandlers`) estão inserindo novos links encontrados e removendo URLs para enviar como tarefa simultaneamente.
  * `Set<String> visitedUrls`: Conjunto thread-safe (`ConcurrentHashMap.newKeySet()`) essencial para garantir que uma URL não seja visitada duas vezes (evitando loops infinitos no crawler).
  * `AtomicInteger activeWorkers`: Resolve a exigência de sincronização no término. Garante a soma e subtração atômica do número de tarefas em execução no momento, evitando *Race Conditions*.
  * `class WorkerHandler (Runnable)`: Thread dedicada para manter a conexão contínua (socket persistente) com um Worker.
* **Funções**:
  * `start()`: Instancia o server e fica em loop infinito no `accept()`.
  * `isFinished()`: Lógica central de encerramento do crawler. Retorna `true` apenas se a fila está vazia **E** não há nenhuma tarefa (`activeWorkers`) rodando pela rede.
* **Por que foram utilizadas**: Usar as estruturas prontas do pacote `java.util.concurrent` (ao invés de arrays normais com blocos `synchronized` genéricos) é o estado da arte no Java para máxima performance em concorrência, impedindo gargalos.

## 3. `com.crawler.worker.Worker`
O cliente trabalhador que executa a carga pesada de parseamento (parsing) e regras de negócio.
* **Estruturas Internas**:
  * `ExecutorService executor`: Um pool de threads fixo (`FixedThreadPool`) que restringe a capacidade do worker (ex: 4 ou 8 threads). 
  * `Semaphore semaphore`: Usado para o controle de fluxo (*Backpressure*). Impede que o Worker solicite tarefas ao Coordenador caso todas as suas threads internas já estejam atarefadas.
* **Funções**:
  * `start()`: Estabelece a conexão e implementa o loop da máquina de estados (envia `REGISTER`, `READY`, recebe `TASK`, aguarda `WAIT` ou desliga em `SHUTDOWN`).
  * `processUrl()`: Lógica executada de forma assíncrona. Conecta no `DataServer` e aplica validações no HTML/conteúdo retornado.
* **Lógica Funcional (Java Streams)**:
  * O processamento do Worker foi construído baseado na API de Streams. A string contendo vários links recebida do DataServer é transformada em Stream.
  * Múltiplos `Predicates` atuam como filtros: removem links inválidos (`isValidLink`), impedem loops apontando para si próprio (`isNotSelfRef`) e verificam ocorrências de certas strings no texto (`isSports`) para categorização de conteúdo.
* **Por que foram utilizadas**: O Semáforo garante que o worker opere no seu limite sem explodir a memória (Out of Memory). Os `Streams` tornam o código declarativo, limpo e extremamente performático em Java moderno, cumprindo a restrição principal do projeto sobre tratamento de dados funcionais.

## 4. `com.crawler.common.Protocol`
* **Definições**: Contém apenas campos `public static final String`.
* **Por que foram utilizadas**: Hardcodar as strings de comandos (`READY`, `FOUND:`) dentro dos Workers e Master gera acoplamento alto e chance de bugs por digitação. Centralizar essas strings numa classe do pacote `common` permite que todos respeitem o contrato estrito de sockets criado.
