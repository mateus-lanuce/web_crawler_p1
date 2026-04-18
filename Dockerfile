# Build stage
FROM maven:3.9.6-eclipse-temurin-26-jammy AS build
WORKDIR /app
COPY pom.xml .
# Dependencias nao sao necessarias nesse pom basico, mas podemos rodar go-offline se tivessemos
COPY src ./src
RUN mvn clean compile

# Runtime stage
FROM eclipse-temurin:26-jre-jammy
WORKDIR /app
COPY --from=build /app/target/classes /app/classes
COPY mock_internet.csv .
COPY config.txt .

# O script de entrypoint vai determinar qual classe iniciar
# DATA_SERVER, COORDINATOR ou WORKER
ENV ROLE=""

CMD if [ "$ROLE" = "DATA_SERVER" ]; then \
      java -cp /app/classes com.crawler.dataserver.DataServer; \
    elif [ "$ROLE" = "COORDINATOR" ]; then \
      java -cp /app/classes com.crawler.coordinator.Coordinator; \
    elif [ "$ROLE" = "WORKER" ]; then \
      java -cp /app/classes com.crawler.worker.Worker; \
    else \
      echo "ROLE nao definida. Use DATA_SERVER, COORDINATOR ou WORKER"; \
      exit 1; \
    fi
