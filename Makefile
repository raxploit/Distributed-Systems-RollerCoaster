-include .env
export

VM_HOST ?= $(STATION_HOST)
VM_USER ?= arch
VM_REPO_DIR ?= $(CURDIR)

VM_SSH := $(VM_USER)@$(VM_HOST)
VM_COMPOSE := ssh $(VM_SSH) 'cd $(VM_REPO_DIR) && docker compose -f compose.yaml'
LAPTOP_COMPOSE := docker compose -f compose.laptop.yaml

.PHONY: vm-up vm-down pax-up pax-down up down vm-logs pax-logs logs vm-ps pax-ps ps vm-config pax-config config

vm-up:
	$(VM_COMPOSE) up -d --build

vm-down:
	$(VM_COMPOSE) down

pax-up:
	$(LAPTOP_COMPOSE) up -d --build

pax-down:
	$(LAPTOP_COMPOSE) down

up: vm-up pax-up

down: pax-down vm-down

vm-logs:
	$(VM_COMPOSE) logs -f --tail=100

pax-logs:
	$(LAPTOP_COMPOSE) logs -f --tail=100

logs: vm-logs

vm-ps:
	$(VM_COMPOSE) ps

pax-ps:
	$(LAPTOP_COMPOSE) ps

ps: vm-ps pax-ps

vm-config:
	$(VM_COMPOSE) config

pax-config:
	$(LAPTOP_COMPOSE) config

config: vm-config pax-config
