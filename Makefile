-include .env
export

COMPOSE := docker compose -f compose.yaml -f compose.clients.yaml

.PHONY: gen up down logs ps config

gen:
	./docker/generate-compose.sh

up: gen
	$(COMPOSE) up -d --build

down: gen
	$(COMPOSE) down

logs:
	$(COMPOSE) logs -f --tail=100

ps:
	$(COMPOSE) ps

config: gen
	$(COMPOSE) config
