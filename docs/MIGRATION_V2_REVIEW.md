# V2 적용 순서 점검 — 2026-11-19 KST

검증 결과 V2 SQL을 바꿀 필요는 발견되지 않았다. V2를 중앙에 먼저 반영할 수 있으며, 예산 포트 완성은
그 이후의 실제 API 연결 조건이다. 다만 실제 팀원 2 DB의 적용 상태는 아직 전달받지 못했으므로,
아래 결과는 실제 DB 이관 완료를 의미하지 않는다. 기본 애플리케이션의 등록503 동작도 그대로다.

## 검증 대상과 결과

Java21 / Gradle8.14.7 / Flyway11.7.2 / PostgreSQL16.16-bookworm Testcontainers로 5개 테스트를 실행했다.
실제 개발/운영 DB에는 접속하거나 변경하지 않았다. SQL 원문은 Git blob 식별자를 대조해 고정했다.

| 파일 | 출처 commit | Git blob SHA |
|---|---|---|
| V1__common_users_and_session.sql | 중앙 cc4b736 | e989bbbb2e6aa9f82d76596d4d0b0c7ef8dea945 |
| V2__loans_and_ledger.sql | #80 / 884aacf | 1419c7b71981d27bdf225af0d4eb54c46d5ca5d9 |
| V3__budgets_and_catalog.sql | #15 / e6619c7f222f6b377864fe72828e6d416fc0a932 | bddc7315a0cfc86d362c652d6744d60f2fda8241 |
| V5__reports_and_settings.sql | #46 / a6de19429296189575215668abfaca177ba96790 | 7037d1a3736f0393b4e48cce15c9fbdf8dd96483 |

위 SHA는 **Git blob**이고 실제 DB의 flyway_schema_history.checksum은 **Flyway CRC32**다. 둘을 서로 비교하지 않는다.
실행에서 확인한 Flyway11.7.2의 CRC32 대조값은 V1=894218023, V2=-512304633,
V3=176739105, V5=-1330788155이다. 음수 checksum도 정상 정수 표현이다.

| DB 시작 상태 | 검증 결과 |
|---|---|
| 빈 DB | 기본 outOfOrder=false로 V1→V2→V3→V5 적용·validate·재실행 성공 |
| V1·V3 | 기본 설정의 새 V2 적용은 차단. V2 일회성 outOfOrder=true 적용 성공 |
| V1·V5 | 위와 동일. 적용 묶음은 기존 V1/V5와 신규 V2만 포함 |
| V1·V3·V5 | 위와 동일. 기존 사용자/재정/배분/리포트 행과 이력 체크섬 보존 |
| 기존 V3 파일 누락/체크섬 변경 | validate 실패 확인. repair/이력 삭제로 우회하지 않음 |

세 가지 기존 DB 경우 모두 이관 후 컬럼/제약조건/인덱스를 동일 버전의 순차 신규 설치와 비교해 일치했다.
기존 테이블의 전체 행과 기존 Flyway 이력(version/checksum/script/rank/success)이 변경되지 않았다.
일회성 적용 후 outOfOrder=false로 복귀하여 validate와 추가 이관 0건도 확인했다.
읽기 전용 psql 확인 SQL은 세 가지 이관 후 스키마 및 테이블이 없는 스키마에서도 실행 성공했다.

V4는 아직 구현되지 않아 검증하지 않았다. 빈 DB의 현존 1/2/3/5 SQL 조합이 성공한다는 결과를
최종 V1→V2→V3→V4→V5 전체 검증으로 보고하지 않는다. 앞으로의 새 설치/배포는 가능한 한 실제
V4를 먼저 준비한 후 V5를 적용한다. placeholder V4를 만들지 않는다. 이미 V5인 DB에 나중에 V4를
도입할 때는 실제 V4를 대상으로 별도 순서 점검이 필요하다.

## 실제 DB 상태 확인

[읽기 전용 SQL](ops/inspect_flyway.sql)을 팀원 2의 대상 DB에서 psql로 실행한다. 테이블이 없을 때도
검사 메시지로 구분하며 개인정보/재정 payload는 출력하지 않는다. 기본 schema는 public이다.

```sh
psql -v audit_schema=public -f docs/ops/inspect_flyway.sql
```

이미 접속 환경이 설정된 psql 기준이며, 접속 비밀번호/URL을 소스나 공개 Issue에 넣지 않는다.
확인할 항목은 전체 installed_rank/version/script/checksum/success, V2 테이블 유무, 참조 불일치 건수다.
최댓값 version만 보지 않는다. 아직 없는 V2에 맞춰 이력 테이블을 직접 채우거나 baseline을 올리지 않는다.

## 상태별 적용 방침

1. V2가 이미 성공 적용되고 원문/체크섬이 일치하는 DB: 다시 적용하지 않고 validate한다.
   다른 미적용 버전은 별도 적용 계획에 따른다.
2. V1만 있는 DB: 통상적인 V2 순차 migrate와 validate. 전역 outOfOrder 변경 없음.
3. V3/V5가 먼저 있는 DB: 먼저 앱/worker 쓰기를 멈추고 백업·복원 가능 여부와 이력을 확인한다.
   적용된 모든 SQL의 원문/체크섬이 맞는 묶음에 V2만 추가하고, 마이그레이션 전용 실행에서
   outOfOrder=true를 이번 V2 적용에 한해 사용한다. 이번 검증처럼 기존 적용 버전 외의 다른 신규
   V3/V5를 함께 실행하지 않는다. 실제 실행 명령은 DB 확인 결과와 사용 중인 실행 도구에 맞춰 정한다.
4. 즉시 기본값 false로 복귀하여 validate·재실행 0건·기존 행 및 이력 보존을 확인한다.
5. checksum 불일치, failed 이력, 수동 생성 테이블이 발견되면 원인 조사 전 적용하지 않는다.
   원본 SQL 수정, clean/drop/truncate, repair/이력 삭제·수정으로 통과시키지 않는다.
6. 위 단계는 스키마 이관이다. 아래 참조 정합성과 실제 포트 연결 확인 전 앱의 금융 기능을 재개하지 않는다.

웹앱을 일회성 마이그레이션 실행기로 대신 기동하면 worker/다른 신규 마이그레이션도 실행될 수 있다.
애플리케이션 설정이나 운영 환경에 outOfOrder=true를 영구 저장하지 않는다.

## 기존 loan_id 참조 검토

현재 V3 monthly_allocations.loan_id 및 V5 report_jobs.loan_id/후보 payload는 V2 loans FK가 없다.
테스트에서 이전 임시 ID를 가진 배분/리포트를 넣었더니 V2 적용 후에도 원본대로 남았고,
없는 대출 참조로 정확히 검출됐다. **실제 팀원 2 DB에 이런 데이터가 있다는 확인은 아직 없다.**

- SQL은 대출 없음/소유자 불일치 건수와 검토할 payload 건수를 출력한다.
- V2가 없을 때 생성된 대출 ID 참조는 원래 대출의 존재를 검증할 수 없다. 나중에 새 대출 ID 숫자가
  우연히 같아져도 같은 리소스라고 간주하지 않는다. 가능하면 이관 전에 참조 상태를 먼저 확인한다.
- 예전 배분·리포트·후보의 원본/사용자/대출 매핑을 담당자가 검토해야 한다. 후보 payload의 중첩 ID도
  별도로 확인한다. 이번 작업은 기존 행을 자동 삭제/재번호 부여하거나 가짜 loan 행을 생성하지 않는다.
- 불일치가 있으면 이를 보존한 채 해당 기능 적용을 중단하고, 담당 도메인의 정정/보관 정책을 결정한다.
  외래키를 나중에 추가할 때도 이 정합성 확인을 선행한다.

## #80 및 후속 작업

V2 스키마의 기술 검증은 통과했다. 예산 전체 완성을 기다리며 #80을 붙잡아두는 순환 대기는 해소할 수 있다.
#80 중앙 반영과 팀원 2 기존 DB 실제 이관은 별도 작업으로 관리한다. 실제 DB 회신이 없다고 중앙 V2
코드 검증 자체가 실패한 것은 아니지만, 기존 DB에서 즉시 bootRun해도 된다고 안내하지 않는다.
이 점검은 #80 Ready 전환/merge를 자동 실행하지 않았다.

다음 순서는 #80 중앙 반영 → #82 조회 변경만 별도 PR → 실제 LoanQueryPort/PlanQueryPort 및 예산 연결이다.
등록 정상 경로의 실제 재계산은 아직 미검증이며 연결 전503/무저장 정책을 유지한다.

## 팀원 2 전달 문구 — 작성만, 발송하지 않음

> V2(#80)와 기존 V3(#15)/V5(#46) SQL을 원문 해시 고정해 PG16.16/Flyway11.7.2에서 검증했습니다.
> 새 설치 및 V3/V5 선적용 DB의 V2 일회성 out-of-order, 기존 행/이력 보존과 기본 설정 복귀까지 통과했습니다.
> 현재 개발 DB의 실제 적용 상태는 아직 몰라서 먼저 docs/ops/inspect_flyway.sql의 읽기 전용 결과를 부탁드립니다.
> 버전·script·checksum·success와 참조 불일치 건수만 확인하면 됩니다. 키/접속정보/금융 payload는 보내지 않아도 됩니다.
> V2가 없는 상태에서 만든 배분/리포트의 loan_id가 있다면 새 대출과 임의로 연결하지 않고 정합성 검토가 필요합니다.
> 기존 DB는 확인 전 새 V2로 바로 bootRun하지 않고 이력에 맞춘 일회성 이관을 진행하는 방침입니다.
> V2 중앙 반영과 실제 예산 재계산 연결은 분리해 진행하겠습니다. V3/V5 원문이나 전역 outOfOrder 설정은 바꾸지 않았습니다.

## 재현 자료

[재현용 테스트](migration-audit/java/MigrationAuditTest.java), [Gradle init](migration-audit/audit.init.gradle).
공유 SQL을 production migration 디렉터리에 복사하지 않고 `.tools/migration-audit/assets/`에만 준비한다.
네 파일명은 위 표와 동일하게 저장한다. 테스트가 각 원문의 Git blob SHA를 확인한 뒤 임시 schema를 만든다.
V1/V2는 해당 commit에서, V3/V5는 위 고정 commit의 공개 Fork 원문에서 얻는다.

```sh
./gradlew -I docs/migration-audit/audit.init.gradle migrationAudit
```

Java21/Docker가 필요하며 기존 프로젝트 테스트 의존성만 사용한다. 실제 DB 연결 인자를 받지 않고
별도 PostgreSQL Testcontainer에서만 수행한다. 일반 test/운영 설정은 바꾸지 않는다.
검증 결과는 build/reports/tests/migrationAudit/ 및 build/test-results/migrationAudit/에 생성된다.

공식 근거: [outOfOrder](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-out-of-order-setting),
[validate](https://documentation.red-gate.com/flyway/reference/commands/validate).
