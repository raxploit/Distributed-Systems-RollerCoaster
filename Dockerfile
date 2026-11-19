# Ein Image für alle Rollen: Java (Station/Passagiere) + Python (Wagen).
# Der Thrift-Compiler wird nicht benötigt – gen-java/gen-py liegen im Repo.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY lib lib
COPY src/rollerCoaster src/rollerCoaster
COPY gen-java gen-java
RUN javac -cp "lib/*" -d build src/rollerCoaster/*.java gen-java/rollerCoaster/*.java

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-venv \
 && rm -rf /var/lib/apt/lists/* \
 && python3 -m venv /opt/venv

COPY requirements.txt /tmp/requirements.txt
RUN /opt/venv/bin/pip install --no-cache-dir -r /tmp/requirements.txt

ENV PATH="/opt/venv/bin:${PATH}"
ENV PYTHONPATH="/app/gen-py"

COPY --from=build /app/build build
COPY lib lib
COPY gen-py gen-py
COPY src/python src/python
COPY docker docker

CMD ["java", "-cp", "build:lib/*", "rollerCoaster.StationServer", "4"]
