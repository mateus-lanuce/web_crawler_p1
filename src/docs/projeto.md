1. Web Crawler.
Um Web Crawler (também chamado de bot ou spider) funciona de forma muito semelhante a uma pessoa
navegando na internet, mas com uma velocidade e memória superiores. Ele é um algoritmo projetado para
navegar de forma metódica e automatizada pela rede, saltando de link em link.
É um dos pilares de sistemas como Google ou Bing. No contexto de programação concorrente e
distribuída, ele é um desafio excelente porque envolve IO Bound (esperar a rede) e CPU Bound (processar o
HTML).
● Funcionamento:
○ A lista de sementes.
■ O crawler não adivinha onde os sites estão. Ele recebe uma lista inicial de endereços
confiáveis (as "sementes").
● Analogia: Imagine que você recebeu uma lista com o endereço de 5 grandes
bibliotecas para começar sua pesquisa.
○ A fila de fronteira.
■ Assim que o crawler visita uma semente, ele lê o código HTML daquela página. O seu
trabalho principal é identificar todos os hiperlinks (<a href="...">) presentes.
■ Esses novos links encontrados são colocados em uma "lista de espera"(a fila).
● Antes de adicionar à fila, o crawler verifica se já visitou aquele link antes, para
não ficar em loop infinito.
○ Download e indexação.
■ Enquanto o crawler "anota" os links para o futuro, ele também "lê" o conteúdo da
página atual.
■ Ele extrai textos, imagens, palavras-chave e metadados.
■ Essas informações são enviadas para um Indexador (um banco de dados gigantesco),
que é o que o Google consulta quando você faz uma busca, por exemplo.
● Analogia: É como se, ao entrar na biblioteca, você tirasse uma foto de cada
página de cada livro e guardasse em um arquivo organizado por assunto.
○ Repetição (recursão).
■ O crawler pega o próximo link da fila e repete o processo:
● Acessa a página.
● Extrai novos links.
● Salva o conteúdo.
● Move-se para o próximo link.
2. Proposta de arquitetura do sistema.
● Servidor de dados: Um processo que escuta em uma porta e, ao receber o nome de uma "URL", devolve
uma lista de strings (links) via sockets.
○ Estrutura interna: Um Map<String, List<String>> que mapeia URLs para seus links.
○ Concorrência: Utiliza um ExecutorService para gerenciar cada conexão de Worker que chega.
Cada requisição de "GET LINKS" roda em uma thread separada para não travar o servidor.
○ A seguir, considere um exemplo do mock da internet.
Map<String, List<String>> internetMock = Map.of(
 "google.com", List.of("gmail.com", "youtube.com", "noticias.com"),
 "noticias.com", List.of("esporte.com", "clima.com", "google.com"),
 "esporte.com", List.of("futebol.com", "basquete.com"),
 "futebol.com", List.of() // Fim da linha
);
○ Alternativa para geração de dados fictícios:
■ Crie um arquivo .txt ou .csv simples onde cada linha é:
ID_DA_PAGINA;TEXTO_DA_PAGINA;LINKS_SEPARADOS_POR_VIRGULA.
■ O servidor de dados mockados lê esse arquivo para a memória ao iniciar.
● Coordenador (Master): Gerencia a fila de prioridades e decide qual Worker recebe qual URL.
○ Componentes:
■ BlockingQueue<String>: URLs aguardando processamento.
■ ConcurrentHashMap.KeySet: Registro global de URLs visitadas.
■ ServerSocket: Escuta conexões dos Workers para receber resultados e enviar novas
tarefas.
● Workers (Trabalhadores): Processos que possuem um ExecutorService interno para processar múltiplas
URLs simultaneamente.
○ Estrutura Interna:
■ ExecutorService: Um pool de threads para processar várias URLs em paralelo.
■ Socket Cliente (Coordenador): Para pedir/receber tarefas.
■ Socket Cliente (Servidor de dados): Para "baixar" o conteúdo das URLs simuladas.
3. Funcionamento.
● Inicialização e registro.
○ O Servidor de dados sobe e carrega o mapa de URLs/Links na memória.
■ Mil links, cada qual com mil itens.
○ Os Workers iniciam e se conectam ao Coordenador, informando quantas threads têm
disponíveis (capacidade de processamento).
● Distribuição de carga.
○ O Coordenador possui uma BlockingQueue com as URLs iniciais (sementes).
○ Ele usa uma thread para distribuir tarefas: quando um Worker está livre, o Coordenador envia
uma URL via Socket.
○ Controle de duplicidade: O Coordenador mantém um ConcurrentHashMap de URLs já enviadas
para evitar loop circular.
● Processamento paralelo.
○ O Worker recebe uma URL do Coordenador.
○ Ele despacha essa tarefa para seu ExecutorService local.
○ A thread trabalhadora abre um Socket com o Servidor de dados, solicita os links daquela URL e
recebe a resposta.
○ O Worker processa a resposta (ex: limpa strings, remove links inválidos ou faz filtros
específicos) usando streams de Java (é permitido usar streams que não foram vistas, contanto
que sejam explicadas).
■ Funcionalidades:
● Validação de integridade.
○ O Worker verifica se o ID_DA_PAGINA está presente ou relacionado
dentro da própria lista de LINKS_SEPARADOS_POR_VIRGULA.
■ Assim, ele garante que a página não é um "beco sem saída" ou
uma "ilha". Ele pode filtrar links que apontam para o próprio
ID (auto-referência) para evitar ciclos inúteis na fila do
Coordenador.
● Categorização por conteúdo.
○ O Worker atua como um classificador usando as Interfaces Funcionais
para aplicar diferentes regras de negócio.
■ Através de um Predicate, o Worker verifica se o texto contém
padrões (ex: Termos específicos sobre um determinado
assunto ou categorias como "Esportes"). Ele anexa essa
"etiqueta" ao resultado enviado ao Coordenador.
● Feedback.
○ Após processar, o Worker envia de volta ao Coordenador a lista de novos links encontrados.
○ O Coordenador recebe esses links, filtra os que já foram visitados e os coloca na fila para serem
distribuídos novamente.
● Desafio de Sincronização: Como todos os processos param?
○ Lógica: O Coordenador só pode encerrar quando:
■ A fila de URLs estiver vazia.
■ Todos os Workers reportarem que estão "Idle" (ociosos).
■ Nenhuma mensagem de "Novos Links" estiver em trânsito no Socket.
4. Exemplo de protocolo de comunicação (Sockets).
● Para facilitar, você pode criar um protocolo simples de texto:
○ Worker -> Servidor: GET /google.com HTTP/1.1
○ Servidor -> Worker: LINKS: gmail.com, youtube.com, maps.com
○ Worker -> Coordenador: FOUND: gmail.com, youtube.com, maps.com FROM
google.com
5. Observações.
○ A apresentação vai ser feita no laboratório com as simulações executadas em duas máquinas,
no mínimo.