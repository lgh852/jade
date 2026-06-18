# Jade

JadeHR(eHR) scraping API PoC.

사내 JadeHR에 로그인한 뒤 근태 캘린더 조회, 출근 등록, 퇴근 등록을 서버 API로 감싼 Spring Boot Kotlin 프로젝트다. JadeHR이 공개 API를 제공하지 않아서 브라우저가 호출하는 `menuAction.do`, `commonAction.do` 흐름을 재현한다.

## Tech Stack

- Kotlin
- Spring Boot 3.5
- WebFlux `WebClient`
- Jsoup
- Gradle

## Package Structure

```text
co.kr.ghlee.jade
├── common
│   └── api                         # 공통 API 예외 응답
└── api
    └── jadehr
        ├── presentation            # REST controller, request/response DTO
        ├── application             # use case service
        ├── domain                  # domain model
        ├── exception               # JadeHR 예외 계층
        └── infrastructure
            ├── client              # JadeHR HTTP client, cookie jar
            ├── config              # properties, WebClient config
            ├── parser              # JadeHR HTML/XML parser
            └── session             # in-memory session cache
```

모노레포에 올릴 때 다른 API/도메인이 추가되더라도 `api/{api-name}` 기준으로 경계가 나뉘도록 구성했다.

## Configuration

기본 설정은 [application.properties](src/main/resources/application.properties)에 있다.

```properties
jadehr.base-url=https://ehr.jadehr.co.kr
jadehr.default-company-code=2202010
jadehr.connect-timeout=5s
jadehr.response-timeout=20s
jadehr.trust-store-path=
jadehr.trust-store-password=
```

JadeHR 인증서 체인을 JVM이 신뢰하지 못하면 PKCS12 truststore를 생성해서 `jadehr.trust-store-*` 값을 맞춘다.

## Run

```bash
./gradlew bootRun
```

## Docker

로컬 이미지 빌드:

```bash
./gradlew bootJar
docker build -t jade-api:latest .
```

Compose 실행:

```bash
./gradlew bootJar
docker compose up -d --build
```

운영 환경에서는 `docker-compose.yml` 기준으로 `jade-api` 컨테이너를 `8080`에 띄운다. Health check는 `/actuator/health`를 사용한다.

## GitHub Actions Deploy

PR을 배포 대상 브랜치에 merge하면 `.github/workflows/deploy.yml`이 실행되어 서버에 자동 배포한다. 수동 실행은 GitHub Actions의 `Deploy API` workflow에서 `Run workflow`로 처리한다.

필요한 repository secrets:

```text
JADE_API_HOST=<api server host>
JADE_API_SSH_USER=<ssh user>
JADE_API_SSH_PORT=<ssh port>
JADE_API_SSH_KEY=<private key>
JADE_API_URL=<public api base url>
```

서버의 `/opt/jade-api/.env`는 배포 중 유지된다. 최초 1회는 아래 값이 서버에 있어야 한다.

```text
JADE_DATASOURCE_URL=<jdbc url>
JADE_DATASOURCE_USERNAME=<db user>
JADE_DATASOURCE_PASSWORD=<mysql jade user password>
JADE_AUTH_CRYPTO_SECRET=<credential encryption secret>
TELEGRAM_BOT_TOKEN=<telegram bot token>
TELEGRAM_CHAT_ID=<telegram chat id>
```

Health check:

```bash
curl 'http://localhost:8080/actuator/health'
```

## API

### Auth User

`authKey`는 서버에서 해시하지 않고 DB에 그대로 저장된 값과 exact match 한다. JadeHR 비밀번호는 서버 내장 키 기반 AES-GCM으로 암호화 저장한다.

```bash
curl -X POST 'http://localhost:8080/api/jadehr/auth-users' \
  -H 'Content-Type: application/json' \
  -d '{
    "authKey": "external-fixed-key",
    "companyCode": "2202010",
    "jadeHrUserId": "jadehr-id",
    "jadeHrPassword": "jadehr-password",
    "active": true
  }'
```

### Login Check

로그인 성공 시 세션 쿠키를 메모리에 저장한다. 이후 `userId` 기반 API에서 재사용한다.

```bash
curl -X POST 'http://localhost:8080/api/jadehr/session/login-check' \
  -H 'Content-Type: application/json' \
  -d '{
    "companyCode": "2202010",
    "userId": "jadehr-id",
    "password": "jadehr-password"
  }'
```

### Attendance Calendar

로그인 세션 재사용:

```bash
curl 'http://localhost:8080/api/jadehr/attendance-calendar?userId=jadehr-id&year=2026&month=6'
```

근무 항목만 간단히 조회:

```bash
curl 'http://localhost:8080/api/jadehr/attendance-calendar/work-items?userId=jadehr-id&year=2026&month=6'
```

세션 없이 요청마다 로그인:

```bash
curl -X POST 'http://localhost:8080/api/jadehr/attendance-calendar' \
  -H 'Content-Type: application/json' \
  -d '{
    "companyCode": "2202010",
    "userId": "jadehr-id",
    "password": "jadehr-password",
    "year": 2026,
    "month": 6
  }'
```

### Attendance Record

출근/퇴근 저장 전 준비 및 검증 정보 확인:

```bash
curl -X POST 'http://localhost:8080/api/jadehr/attendance-records/prepare' \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": "jadehr-id",
    "type": "START"
  }'
```

출근 등록:

```bash
curl -X POST 'http://localhost:8080/api/jadehr/attendance-records/start' \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": "jadehr-id",
    "confirm": true
  }'
```

퇴근 등록:

```bash
curl -X POST 'http://localhost:8080/api/jadehr/attendance-records/end' \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": "jadehr-id",
    "confirm": true
  }'
```

`confirm=true`는 실수 호출 방지용 서버 측 가드다.

키 기반 자동 로그인 + 출근:

```bash
curl 'http://localhost:8080/api/jadehr/attendance-records/start/auto' \
  --get --data-urlencode 'authKey=external-fixed-key'
```

키 기반 자동 로그인 + 퇴근:

```bash
curl 'http://localhost:8080/api/jadehr/attendance-records/end/auto' \
  --get --data-urlencode 'authKey=external-fixed-key'
```

## JadeHR Flow Notes

JadeHR의 근태 등록은 단일 API 호출이 아니다. 브라우저 기준 흐름은 아래 순서다.

1. 메인 페이지 로그인 세션 확보
2. 근태 팝업 `menuAction.do` 오픈
3. `commonAction.do` 검증 호출
4. `commonAction.do` 저장 호출
5. 메인/캘린더 갱신 호출

출근 등록 HAR 기준 주요 필드:

```text
S_GUBUN=STA
S_YMD=yyyyMMdd
S_STD_YMD=yyyyMMdd
S_STD_TIME=HHmm
F_STD_YMD=yyyy.MM.dd
F_STD_TIME=HH:mm
S_ATTEND_MANAGE=0040
S_WORK_PLAN_TYPE=000
S_FORWARD=xsheetResultXML
```

JadeHR가 `S_DSCLASS`, `S_DSMETHOD`, `S_ENC_OTP_KEY`, `S_CSRF_SALT`, `__viewState`를 페이지마다 동적으로 바꾸기 때문에 하드코딩하지 않고 HTML에서 파싱한다.

## Debugging With HAR

JadeHR 동작이 바뀌면 DevTools에서 HAR를 받아 비교한다.

1. Chrome DevTools `Network`
2. `Preserve log` 체크
3. 로그 clear
4. JadeHR 화면에서 동작 수행
5. `Export HAR (sanitized)...`
6. `commonAction.do` 요청들의 form-data 비교

민감한 쿠키/세션 헤더는 커밋하거나 공유하지 않는다.

## Production Notes

- 현재 세션 저장소는 in-memory다. 재시작/다중 인스턴스 환경에서는 Redis 등 외부 저장소로 교체한다.
- 운영 배포 시 credential 저장은 KMS 기반 암호화 저장소로 분리한다.
- JadeHR 화면/스크립트 변경에 취약한 구조다. HAR 기반 회귀 확인을 남겨두는 것이 좋다.
- AWS 배포 시 private subnet 애플리케이션이면 JadeHR outbound 경로를 NAT Gateway 또는 사내망 연동 경로로 열어야 한다.

## Test

```bash
./gradlew test
```
