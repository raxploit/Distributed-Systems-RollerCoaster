# Zwei schlanke Images: Java (Station + Passagiere) und Python (Wagen).
# Der Thrift-Compiler wird nicht benötigt – gen-java/gen-py liegen im Repo.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY lib lib
COPY src/rollerCoaster src/rollerCoaster
COPY gen-java gen-java
RUN javac -cp "lib/*" -d build src/rollerCoaster/*.java gen-java/rollerCoaster/*.java

# Station und Passagiere: nur JRE, kompilierte Klassen und Thrift-Jars.
FROM eclipse-temurin:21-jre AS java
WORKDIR /app
COPY --from=build /app/build build
COPY lib lib
CMD ["java", "-cp", "build:lib/*", "rollerCoaster.StationServer", "4"]

# Wagen: nur Python und der Python-Thrift-Stub.
FROM python:3.13-slim AS python
WORKDIR /app
COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt
COPY gen-py gen-py
COPY src/python src/python
ENV PYTHONPATH=/app/gen-py
ENV PYTHONUNBUFFERED=1
CMD ["python3", "-u", "src/python/wagon_runner.py"]
