### 1. Como o Coordenador usa as Threads dos Workers?
Na arquitetura atual que construímos, o Coordenador funciona no modelo **"Push/Pull" dinâmico**, mas ele **não gerencia as threads diretamente**. Quem faz a gestão é o próprio Worker. Funciona assim:

1.  **O Worker é "fominha":** Ele tem um `ExecutorService` (um pool de threads, ex: 4 ou 8). No loop principal, o Worker manda um `READY` para o Coordenador.
2.  **O Coordenador entrega:** O Coordenador tira uma URL da fila e manda de volta (`TASK pageX.com`).
3.  **O Worker delega:** O Worker recebe a URL, joga para uma de suas threads processar (`executor.submit(...)`) e, **imediatamente**, volta a mandar um `READY` pedindo mais trabalho, sem esperar a thread anterior terminar.
4.  **A informação de Capacidade (`REGISTER`):** O comando `REGISTER` que criamos serve para o Coordenador ter **visibilidade** (logging e monitoramento) da força de trabalho da rede. Na lógica atual, o Coordenador não limita o envio. Se ele tiver 1000 URLs, ele vai entregando conforme os Workers pedem (`READY`). As tarefas ficam enfileiradas na memória do Worker até que uma thread dele fique livre para executar.

*Nota de Arquitetura:* Se quiséssemos um controle mais estrito (para não sobrecarregar a RAM de um Worker fraco, por exemplo), poderíamos alterar o código para que o Worker só mandasse o `READY` se tivesse uma thread ociosa, usando um `Semaphore` interno.

---

### 2. Como funciona o `AtomicInteger` (`activeWorkers`)?
O `AtomicInteger` é o "coração" da sincronização do fim do programa. Ele resolve um problema grave chamado **Race Condition (Condição de Corrida)**.

*   **O Problema:** O Coordenador cria uma nova Thread (o `WorkerHandler`) para cada Worker que se conecta. Se você tem 4 Workers, você tem 4 threads mexendo nas mesmas variáveis do Coordenador ao mesmo tempo. Se usássemos um `int` comum (`int tarefasAtivas = 0;`), e duas threads tentassem fazer `tarefasAtivas--` no exato mesmo milissegundo, o Java poderia se perder na conta e registrar apenas uma subtração em vez de duas.
*   **A Solução (`AtomicInteger`):** Ele garante que as operações de soma e subtração ocorram de forma atômica (indivisível) no nível do processador. É impossível duas threads mexerem nele ao mesmo tempo de forma conflitante.

**Como ele dita o ritmo do Crawler:**
*   Sempre que o Coordenador envia uma URL para algum Worker, ele faz `activeWorkers.incrementAndGet()` (Soma 1).
*   Sempre que um Worker devolve os links encontrados, o Coordenador faz `activeWorkers.decrementAndGet()` (Subtrai 1).

A magia acontece na função `isFinished()`:
```java
private synchronized boolean isFinished() {
    return urlQueue.isEmpty() && activeWorkers.get() == 0;
}
```
**Tradução lógica:** *"O Crawler só terminou se a fila de links estiver VAZIA **E** não houver mais nenhum Worker trabalhando em lugar nenhum."*

Se você verificasse só a fila vazia, o Crawler encerraria no momento exato em que a última URL fosse enviada, cortando o processamento do Worker pela metade antes dele achar novos links! O `activeWorkers` garante que o Coordenador espere pacientemente até a última thread do último Worker dizer: *"Acabei, e não achei mais nada"*.