.PHONY: help build test run server client typecheck compose redis docker clean

help:
	@echo "syncforge — multiplayer state-sync server"
	@echo ""
	@echo "  make test       run the Java test suite (CRDT convergence, wire, concurrency)"
	@echo "  make build      compile the server jar"
	@echo "  make server     run the sync server on :8080 (offline, demo room live)"
	@echo "  make client     run the canvas client on :3000"
	@echo "  make typecheck  type-check the TypeScript client"
	@echo "  make compose    docker compose up (server + client)"
	@echo "  make redis      docker compose with the Redis multi-instance profile"
	@echo "  make clean      remove build output"

build:
	cd server-java && mvn -q -B clean package -DskipTests

test:
	cd server-java && mvn -B test

server:
	cd server-java && mvn -q spring-boot:run

client:
	cd client-ts && npm install && npm run dev

typecheck:
	cd client-ts && npm install && npm run typecheck

compose:
	docker compose up --build

redis:
	SYNCFORGE_PROFILE=redis docker compose --profile redis up --build

docker:
	docker compose build

clean:
	cd server-java && mvn -q clean || true
	rm -rf client-ts/dist client-ts/node_modules
