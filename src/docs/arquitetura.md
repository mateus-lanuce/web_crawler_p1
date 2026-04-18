# Arquitetura e Fluxo de Execução

Este documento explica como os componentes do nosso Distributed Web Crawler se interligam para funcionar de forma paralela, assíncrona e resiliente. O projeto foi desenhado sobre o modelo **Master-Worker**, onde os serviços se comunicam de forma autônoma e exclusivamente via texto puro usando **Sockets TCP**.

## A Visão Geral
Tudo é orquestrado de forma isolada por um ecossistema Docker (configurado no `docker-compose.yml`), que cria uma rede interna local (`crawler_net`). Dentro dessa rede, o **DataServer** atua como a internet inteira, o **Coordinator** atua como o cérebro distribuindo a carga e controlando as URLs, e instâncias de **Workers** atuam como os músculos brutais que vasculham a rede.

---

## O Fluxo de Vida do Crawler (Passo a Passo)

### 1. Inicialização e Descoberta (*Bootstrapping*)
- O contêiner do **DataServer** inicia. Ele carrega o banco de dados simulado (`mock_internet.csv`) para a memória e abre seu Socket (porta 8081).
- O **Coordinator** inicia. Ele injeta a primeira página (*URL Seed*: `page1.com`) dentro de sua fila e abre a sua porta (8080).
- Os **Workers** iniciam (neste projeto, temos múltiplos, alguns com capacidade de 4 threads e outros com 8). Eles se conectam à porta 8080 do Coordenador.

### 2. Controle de Fluxo (*Backpressure* e *Handshake*)
- Assim que o Worker conecta, ele manda o comando `REGISTER X` (onde X é o seu número limite de threads). O Coordenador faz o log dessa capacidade.
- O Worker possui internamente um **Semáforo** inicializado também com a capacidade X. Enquanto ele tem uma permissão sobrando no semáforo (ou seja, uma thread ociosa no pool), ele despacha a mensagem `READY` ao Coordenador.
- **Isso evita sobrecarga**: o Worker jamais requisitará tarefas se sua memória e CPU estiverem totalmente comprometidas pelos downloads em andamento.

### 3. A Delegação e a Execução Assíncrona
- Quando o Coordenador lê a mensagem `READY` de um Worker, ele tira a próxima URL da fila.
- Imediatamente, ele registra em um contador atômico (`activeWorkers`) que existe mais uma tarefa rolando pela rede (+1). Em seguida envia a tarefa: `TASK url.com`.
- Ao receber o comando de tarefa, a thread principal do Worker joga essa atividade no seu `ExecutorService` (rodando em background) e **imediatamente** volta a processar novas mensagens, requisitando mais trabalho se seu semáforo permitir.

### 4. A Regra de Negócio (O *Crawling*)
- A thread que assumiu a tarefa no Worker abre uma nova conexão efêmera com o **DataServer**, solicitando a página (`GET url.com`).
- O DataServer responde com os dados raw: texto descritivo e uma vírgula enorme separando todos os links da página.
- O Worker usa o motor moderno do Java (`Streams` e `Predicates` funcionais) para limpar a sujeira: ele converte as strings numa lista limpa ignorando URLs vazias, ignorando `invalid-link` (teste de integridade) e ignorando links que mandam de volta pra própria página (teste de anti-loop e ilha de nós).
- Ao mesmo tempo, baseando-se no texto vindo no socket, ele classifica a natureza da URL (ex: checa se possui as palavras "futebol", "esporte", "basquete" para etiquetá-la como `ESPORTES`).

### 5. O Feedback
- Com os links extraídos e tratados, o Worker finaliza aquela pequena tarefa.
- Através da mesma conexão persistente do início, ele diz ao Master: `FOUND: novo1.com,novo2.com FROM url.com CATEGORY ESPORTES`.
- Ao fazer isso, o Worker libera seu semáforo interno para pegar novas tarefas no futuro.
- O Master recebe os novos resultados. Ele usa o conjunto de urls visitadas (`ConcurrentHashSet`) para podar qualquer coisa que o sistema já viu antes. Se a URL é inédita, vai para o fim da Fila (`BlockingQueue`).
- Depois de enfileirar, ele decrementa as tarefas correntes do contador atômico (-1).

### 6. Como Tudo Termina? O Encerramento Suave (Graceful Shutdown)
Num sistema distribuído sem banco de dados persistente, saber "quando parar" é o maior desafio. Se checarmos apenas se a "Fila está vazia", corremos o risco de encerrar o programa enquanto um Worker lento está processando a última URL que, de surpresa, poderia descobrir mais mil URLs novas.
- Por isso a regra do `isFinished()` foi usada: O sistema deve morrer se, e somente se: **A FILA ESTÁ VAZIA** E **ACTIVE_WORKERS É ZERO**.
- Quando essas duas condições são simultaneamente `true`, o Coordenador compreende que todas as páginas mapeadas foram sugadas e despacha o comando final: `SHUTDOWN`.
- Ao receber `SHUTDOWN`, os processos dos Workers quebram seu ciclo de Socket infinito e os containers morrem elegantemente, sinalizando o fim da operação de forma limpa, concluindo a simulação do motor de buscas de alta escala.
