# Ledger API: especificación del proyecto

Oct 30, 2026 · @Alex Vargas

Una API de cuentas y transferencias en Java 25 y Spring Boot 4, escrita a mano en noviembre, que debe garantizar que el dinero nunca se duplica ni se pierde: ni con 100 transferencias concurrentes, ni con reintentos, ni si el proceso muere a la mitad. Al terminar queda en tu GitHub como `ledger-api`, con un README que explica cada decisión.

## Alcance y stack

**Entra en noviembre:**

- Cuentas con saldo en una sola moneda por cuenta.
- Transferencias entre cuentas de la misma moneda, con su ciclo de vida completo e idempotencia.
- Concurrencia segura: bloqueo pesimista y, como alternativa, optimista.
- Reporte por cuenta que hace consultas en paralelo.
- Caché y rate limiting con Redis.
- Eventos de transferencia publicados a Kafka con el patrón outbox, y un consumer idempotente.
- Observabilidad: métricas, dashboard y trazas.

**No entra** (algunas cosas van en diciembre): autenticación de usuarios, conversión entre monedas, interfaz gráfica, Kubernetes y despliegue en la nube.

**Stack fijo** (no se discute durante el mes, para no perder tiempo eligiendo):

| Pieza | Qué se usa |
| --- | --- |
| Lenguaje y build | Java 25, Maven |
| Framework | Spring Boot 4.1 (la versión estable que ofrece Spring Initializr): Web, Validation, Data JPA, Actuator |
| Base de datos | PostgreSQL 16, migraciones con Flyway |
| Caché y rate limiting | Redis 7 (semana 3) |
| Mensajería | Redpanda, que habla el protocolo de Kafka, con Spring Kafka (semana 3) |
| Tests | JUnit 5, AssertJ y Testcontainers (PostgreSQL, Redis y Redpanda reales, nada en memoria) |
| Observabilidad | Micrometer, Prometheus, Grafana y OpenTelemetry (semana 4) |
| Entorno local | Docker Compose con todos los servicios |

**Perfiles de Spring:** `application.yml` con lo común, `application-dev.yml` para correr contra el Docker Compose y `application-test.yml` para los tests. Es la pregunta de TCS, puesta en práctica.

## Modelo de datos

Seis tablas, cada una en su propia migración de Flyway (`V1__accounts.sql`, `V2__transfers.sql`, etc.). Aquí está lo que cada tabla debe garantizar; el SQL lo escribes tú. Dinero siempre en `NUMERIC(19,4)`, nunca `float` ni `double`.

| Tabla | Semana | Columnas | Restricciones que importan |
| --- | --- | --- | --- |
| `accounts` | 1 | `id` UUID, `owner_name`, `currency` (3 letras), `balance`, `version`, `created_at` | `balance >= 0` con CHECK; `currency` con CHECK de 3 letras mayúsculas; `version` para bloqueo optimista |
| `transfers` | 2 | `id` UUID, `from_account_id`, `to_account_id`, `amount`, `currency`, `status`, `failure_reason`, `created_at`, `updated_at` | Llaves foráneas a `accounts`; `amount > 0`; `from_account_id <> to_account_id`; `status` con CHECK en los 4 estados; índice `(from_account_id, created_at DESC)` y otro para `to_account_id` |
| `idempotency_keys` | 2 | `key`, `request_hash`, `status`, `response_code`, `response_body` JSONB, `transfer_id`, `created_at` | `key` como PRIMARY KEY: es lo que hace imposible duplicar |
| `transfer_events` | 2 | `id`, `transfer_id`, `from_status`, `to_status`, `occurred_at` | Una fila por cada cambio de estado: es la auditoría |
| `outbox` | 3 | `id` UUID, `aggregate_id`, `event_type`, `payload` JSONB, `created_at`, `published_at` | Índice parcial sobre las filas con `published_at IS NULL` (lo que el publisher busca) |
| `notifications` | 3 | `id`, `event_id`, `account_id`, `message`, `created_at` | `event_id` UNIQUE: es lo que hace idempotente al consumer |

## API

| Método y ruta | Semana | Qué hace | Respuestas |
| --- | --- | --- | --- |
| `POST /accounts` | 1 | Crea una cuenta con saldo inicial | 201 con la cuenta y header `Location`; 400 por validación |
| `GET /accounts/{id}` | 1 | Devuelve la cuenta con su saldo | 200; 404 si no existe |
| `POST /transfers` | 2 | Registra una transferencia. Exige el header `Idempotency-Key` | 202 con la transferencia en `CREATED` y header `Location`; 400 sin la llave o con datos inválidos; 409 si la llave está en uso con otro cuerpo o sigue en proceso; 429 por rate limit (semana 3) |
| `GET /transfers/{id}` | 2 | Devuelve la transferencia y su estado actual | 200; 404 |
| `GET /accounts/{id}/transfers?cursor=&limit=` | 2 | Historial de la cuenta, de la más nueva a la más vieja | 200 con `items` y `nextCursor`; paginación por cursor, no por página |
| `GET /accounts/{id}/summary` | 2 | Reporte: total enviado, total recibido, número de transferencias y las 5 más recientes. Las tres consultas corren en paralelo | 200; 404 |
| `GET /actuator/health` y `/actuator/prometheus` | 4 | Salud y métricas | 200 |

**Formato de errores:** todos los errores salen como `ProblemDetail` (RFC 9457), con `type`, `title`, `status`, `detail` y, en los de validación, la lista de campos con error. Un solo `@RestControllerAdvice` hace el mapeo; ningún controller arma errores a mano.

Ejemplo de cuerpo de `POST /transfers`:

```json
{
  "fromAccountId": "7b1e...",
  "toAccountId": "c42a...",
  "amount": "150.00",
  "currency": "USD"
}
```

El monto viaja como texto para no perder precisión en JSON, y se convierte a `BigDecimal` en el servidor.

## Reglas de negocio, ciclo de vida y concurrencia

**Reglas de negocio:**

1. Ambas cuentas deben existir y tener la misma moneda que la transferencia.
2. El monto es mayor que cero, con máximo 2 decimales.
3. No se puede transferir a la misma cuenta.
4. Ningún saldo puede quedar negativo. Si no hay fondos, la transferencia termina en `FAILED` con `failure_reason = INSUFFICIENT_FUNDS`; no es un error HTTP, es un resultado.
5. **La invariante del sistema:** la suma de todos los saldos nunca cambia por una transferencia. Si en algún test cambia, hay un bug.

**Ciclo de vida de una transferencia.** El `POST` solo registra la transferencia en `CREATED` y responde 202. Un procesador en segundo plano (un `@Scheduled` que corre cada segundo) la ejecuta:

| De | A | Quién | Cómo |
| --- | --- | --- | --- |
| (nada) | `CREATED` | `POST /transfers` | Insert junto con la llave de idempotencia, en una sola transacción |
| `CREATED` | `PENDING` | Procesador | Toma un lote con `SELECT ... FOR UPDATE SKIP LOCKED`, para que dos instancias del procesador nunca agarren la misma fila (es lo mismo que `READPAST` en tu shift-management) |
| `PENDING` | `COMPLETED` | Procesador | Mueve los saldos y cambia el estado en la misma transacción |
| `PENDING` | `FAILED` | Procesador | Sin fondos u otra regla rota; guarda el motivo |

Toda transición es un `UPDATE ... WHERE id = ? AND status = ?` y revisa cuántas filas cambió: si fueron cero, otro proceso ya la movió y no se hace nada. Cada transición escribe una fila en `transfer_events`, y desde la semana 3 también una en `outbox`, siempre dentro de la misma transacción.

**Reglas de concurrencia:**

- **Orden de bloqueo:** al mover saldos se bloquean las dos cuentas con `SELECT ... FOR UPDATE`, **siempre en orden de id**, sin importar cuál es origen y cuál destino. Así dos transferencias cruzadas (A→B y B→A) no se bloquean mutuamente.
- **Alternativa optimista (semana 2):** la misma operación con `@Version` y reintento ante `OptimisticLockException`, con un máximo de intentos. En el README anotas cuándo conviene cada estrategia.
- **Nada de `synchronized` para proteger saldos.** La atomicidad vive en la base de datos, porque la API puede correr en varias instancias.
- **Nada de llamadas de red dentro de una transacción.** La publicación a Kafka pasa por el outbox.
- **Executors:** el reporte en paralelo usa un executor de virtual threads dentro de un try-with-resources, nunca un pool creado por petición que no se cierra.

## Hitos por semana

Cada semana se cierra con `mvn verify` en verde y un commit por día. El trabajo diario está en la columna Bloque 4 de la agenda del plan; aquí está lo que tiene que funcionar al final de cada semana.

### Semana 1: la base

- [ ] `docker compose up` levanta PostgreSQL y la app arranca con el perfil `dev`
- [ ] Migración `V1` con la tabla `accounts` y sus restricciones
- [ ] `POST /accounts` y `GET /accounts/{id}` funcionando, con validación
- [ ] Todos los errores salen como `ProblemDetail` desde un solo `@RestControllerAdvice`
- [ ] Test de integración con Testcontainers: crear una cuenta, leerla, y pedir una que no existe (404)
- [ ] Test: un saldo negativo lo rechaza la base de datos, no solo la validación
- [ ] README v0: cómo correrlo y qué hace; repo `ledger-api` público en GitHub

### Semana 2: transferencias seguras

- [ ] Ejecutar una transferencia con bloqueo pesimista, bloqueando en orden de id
- [ ] **Test de concurrencia:** 10 cuentas y 100 transferencias aleatorias en paralelo. Al final, la suma total de saldos es igual a la inicial, no hay saldos negativos y no hay deadlocks. Se corre 10 veces seguidas sin fallar
- [ ] Versión optimista con `@Version` y reintento, y una sección en el README que compare ambas estrategias
- [ ] Ciclo de vida completo: `POST` responde 202, el procesador pasa de `CREATED` a `PENDING` a `COMPLETED`/`FAILED`, con `SKIP LOCKED` y transiciones condicionales
- [ ] **Test de idempotencia:** la misma petición con la misma llave, 20 veces en paralelo, crea una sola transferencia y las 20 respuestas son iguales
- [ ] Test: la misma llave con otro cuerpo responde 409
- [ ] `GET /accounts/{id}/transfers` con paginación por cursor, y `GET /accounts/{id}/summary` con sus 3 consultas en paralelo

### Semana 3: caché, límites y eventos

- [ ] Redis en el Compose. `GET /accounts/{id}` usa cache-aside, y al completar una transferencia se **borran** las claves de las dos cuentas
- [ ] Test: después de una transferencia, el saldo leído nunca es el viejo
- [ ] Rate limiting en `POST /transfers`: 10 peticiones por minuto por cliente (header `X-Client-Id`), con `INCR` y `EXPIRE`; la petición 11 responde 429
- [ ] Cada transición a `COMPLETED` o `FAILED` escribe su evento en `outbox` en la misma transacción
- [ ] Un publisher lee el outbox y publica en el topic `transfer-events`, con el id de la transferencia como clave; marca `published_at`
- [ ] Un consumer guarda una notificación por evento. **Test:** el mismo evento entregado dos veces deja una sola notificación
- [ ] Un evento que falla 3 veces va a `transfer-events.dlq`
- [ ] Diagrama de arquitectura en el README

### Semana 4: operación y presentación

- [ ] Actuator, Prometheus y Grafana en el Compose, con un dashboard de tasa de peticiones, errores y latencia, más transferencias completadas y fallidas por minuto
- [ ] Trazas con OpenTelemetry: una petición se sigue desde el `POST` hasta el consumer
- [ ] README final: decisiones y trade-offs (pesimista contra optimista, por qué outbox, por qué `SKIP LOCKED`, qué harías con más tiempo)
- [ ] Video de 3 minutos explicando el proyecto en inglés
- [ ] El proyecto agregado al CV en inglés y al perfil de Upwork

## Estructura del proyecto

Paquetes organizados por funcionalidad, no por capa técnica: todo lo de transferencias vive junto. Dentro de cada paquete, el controller es delgado, la lógica está en el servicio y el acceso a datos en el repositorio.

```
ledger-api/
├── docker-compose.yml
├── README.md
└── src/
    ├── main/java/com/alex/ledger/
    │   ├── account/       # Account, AccountController, AccountService, AccountRepository
    │   ├── transfer/      # Transfer, TransferStatus, TransferController, TransferService, TransferProcessor
    │   ├── idempotency/   # IdempotencyKey, IdempotencyService
    │   ├── report/        # SummaryService (parallel queries)
    │   ├── cache/         # cache-aside and rate limiting on Redis (week 3)
    │   ├── outbox/        # OutboxEvent, OutboxPublisher (week 3)
    │   ├── notification/  # TransferEventsConsumer, Notification (week 3)
    │   └── common/        # ProblemDetail handler, shared types
    ├── main/resources/
    │   ├── application.yml
    │   ├── application-dev.yml
    │   └── db/migration/  # V1__accounts.sql, V2__transfers.sql, ...
    └── test/java/com/alex/ledger/   # one integration test class per feature, on Testcontainers
```

**Cómo trabajarlo:** cada parte la escribes a mano en IntelliJ sin IA. Cuando cierres cada hito semanal, me pasas el link del repo y te hago un code review como el de payroll-engine. Si te trabas más de 30 minutos en algo, pregunta el concepto (no la solución) y sigue. Como referencia de cómo se ve un proyecto parecido terminado, está `bank-transfers` en el paquete de código, pero no lo copies: ábrelo solo después de intentar tú cada parte.
