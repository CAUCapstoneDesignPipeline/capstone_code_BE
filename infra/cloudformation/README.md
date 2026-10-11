# AWS P6 실행 파일

2026-10-10 후속 변경: 배포 protocol 4와 manifest schemaVersion 2는 GitHub App·docs 조회·계약 SHA 없이 BE/FE source SHA와 image digest를 사용한다. root production 설정에도 계약 pin·외부 fixture를 넣지 않는다. AI-off의 공개 세 false 값은 `deploy/host/manifest.py`에 고정했다. 앱 배포 전 `capstone-prepare-pipeline`의 새 버전으로 host 파일을 설치하고, `capstone-configure-release`로 `deploy/host/production-config.example.json`의 api-only 설정을 저장한다. 기존 앱/current/pending이 있으면 준비 문서가 거부한다. **runtime 스택은 계속 갱신하지 않는다.** 아래 protocol 3 기록은 이전 설치 이력이다.


2026-10-10: 사용자 승인 후 bootstrap Change Set을 검토·적용하고 host metadata PutParameter 두 경로, Deploy/Install version3, prepare/configure version1과 호스트 protocol3 설치를 실제 확인했다. 설치13개·호스트13개·AWS24개 검증이 통과했다. [파이프라인 가이드](../../../capstone_docs/development/release-pipeline-guide.md)와 [권한 적용 기록](../../../aws-permission-handoff/proposed-p8-2026-10-10/README.md)을 따른다. **runtime는 갱신하지 않았다. 로컬 runtime의 UserData는 실제 stack과 다르므로 bootstrap 설치 결과를 근거로 자동 적용하지 않는다.** main 병합·운영 workflow·앱 배포는 미실행이다.

서울 리전 `378040395204` 계정과 `capstone-deploy` profile 전용이다. 기존 IAM 정책·Guardrail·workload boundary를 변경하지 않는다. production 앱 workflow는 실행하지 않는다.

| 파일 | 역할 |
| --- | --- |
| `build.py` | 검토 가능한 bootstrap/runtime JSON과 비밀값 없는 호스트 파일 묶음 생성 |
| `bootstrap.json` | KMS·ECR·로그·SNS·boundary 적용 IAM/OIDC·고정 SSM document |
| `runtime.json` | VPC·public 앱/private DB subnet·SG·EC2/EIP·private RDS·알림 |
| `runtime-network.json` / `runtime-address-import.json` | 보존 EIP 복구 시 네트워크 선생성 / 기존 네트워크+Address import |
| `seed-secrets.py` | 존재하지 않는 Standard SecureString만 메모리에서 생성·저장; 값 출력·덮어쓰기 없음 |
| `change-set.py` | Change Set 생성·검토·실행·실제 stack 상태 조회 |
| `verify-aws.py` | 실제 AWS 리소스 24개 항목 읽기 검증; secret 값 조회 없음 |
| `stack-policy.json` | DB·호스트·EIP의 삭제/교체 업데이트 차단 |
| `retry-uncreated-database.py` | 실제 DB 없음 확인 후 별도 승인한 실패 Database 재생성만 실행 |
| `../../deploy/host/` | root 소유 고정 스크립트; user-data에 압축 포함 |

Python AWS SDK의 CLI 로그인 profile 지원에는 `boto3`, `botocore[crt]`가 필요하다. 로컬 도구 환경은 `.local/`에 두고 커밋하지 않는다. 아래는 BE 저장소에서 실행하는 신규 구축 순서 예시다. 현재 두 stack은 이미 존재하므로 신규 생성 명령을 그대로 재실행하지 않는다.

```bash
python3 infra/cloudformation/build.py
cfn-lint --config-file infra/cloudformation/.cfnlintrc -t infra/cloudformation/bootstrap.json infra/cloudformation/runtime.json
aws cloudformation validate-template --template-body file://infra/cloudformation/bootstrap.json --profile capstone-deploy --region ap-northeast-2
aws cloudformation validate-template --template-body file://infra/cloudformation/runtime.json --profile capstone-deploy --region ap-northeast-2
python3 infra/cloudformation/change-set.py create bootstrap --name UNIQUE-NAME --db-bootstrap true
python3 infra/cloudformation/change-set.py inspect bootstrap --name UNIQUE-NAME
python3 infra/cloudformation/change-set.py execute bootstrap --name UNIQUE-NAME
python3 infra/cloudformation/change-set.py status bootstrap --name UNIQUE-NAME
python3 infra/cloudformation/seed-secrets.py
python3 infra/cloudformation/change-set.py create runtime --name UNIQUE-NAME
```

`UNIQUE-NAME`은 실제 실행에서 고유 이름으로 바꾼다. create 직후 실행하지 않고 `CREATE_COMPLETE/AVAILABLE`과 diff를 확인한다. 신규 비용은 [실행 기록](../../../capstone_docs/development/aws-infrastructure-execution-2026-10-08.md)의 승인 조건을 따른다. 기존 상태 리소스 삭제/교체, 범위 확대는 별도 승인 대상이다. 도구는 replacement와 일반 removal을 거부한다. 예외는 이름과 나머지 속성이 같은 SSM document의 Content 변경과 `UpdateMethod=NewVersion`뿐이며, 실제 동일 document의 새 버전/default version 변경으로 적용한다. [CloudFormation SSM document 공식 동작](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-ssm-document.html). 도구의 검사는 비용·정책 의미 검토를 대신하지 않는다.

DB bootstrap 완료 뒤 같은 bootstrap 템플릿으로 `--update --db-bootstrap false` Change Set을 검토·실행한다. 삭제 대상은 **임시 `HostBootstrapPolicy`만**이어야 한다. 이후 `capstone-verify-infra` 실제 실행에서 master 접근 거부와 앱 DB TLS를 확인한다. SecureString 값은 로컬 파일·CloudFormation parameter·Output·SSM command argument에 넣지 않는다.

SSM document `capstone-install-host`, `capstone-bootstrap-db`, `capstone-verify-infra`, `capstone-tls`는 입력 없이 지정된 root 소유 파일만 실행한다. `capstone-deploy`는 digest/release/hash와 manifest를 `ENV_VAR`로 전달하며, version3 호스트 파이프라인이 승인 계약·config·실제 이미지와 현재 배포 상태를 검사한 뒤 배포·롤백한다. `ssm-operation.py`는 고정 작업의 operation/exit code와 검증 boolean만 기존 `/capstone/prod/ssm`에 직접 기록한다. raw stdout/stderr와 secret 값은 CloudWatch에 복사하지 않는다. native SSM 로그 내보내기가 요구하는 DescribeLogGroups/CreateLogGroup은 기존 boundary가 허용하지 않아 이를 확대하지 않고 기존 CreateLogStream/PutLogEvents만 사용했다. [SSM CloudWatch 권한 요구](https://docs.aws.amazon.com/systems-manager/latest/userguide/sysman-rc-setting-up-cwlogs.html). 설치 document는 Nginx 준비 설정도 쓰므로 **앱 배포 전 신규 호스트에서만** 사용하고 운영 앱 배포 후에는 검토 없이 재실행하지 않는다.

의도된 제한: ALB/NAT/AI 없음, 외부 80/443만, RDS private/Single-AZ/자동 backup/deletion protection, EBS 암호화/보존, IMDSv2/hop=1 및 Docker metadata 차단. 실제 Free Account가 7일 backup을 거부했고, 별도 승인한 1일 backup으로 생성 성공·available·실제 retention=1을 확인했다. native RDS 로그 export는 현재 실행 정책의 `/capstone/*` 관리 범위에 포함되지 않아 설정하지 않는다. 이를 위한 권한 확대는 하지 않는다. BE/host/SSM 로그 그룹은 14일 보존이며 Nginx와 SSM 상태 로그 수집을 실제 확인했다. BE 로그는 앱 배포 후 검증한다.

`cfn-lint`의 W3010은 실제 계정에서 제공 여부를 조회하고 서울 AZ를 고정했기 때문에 제외한다. Database의 E3691만 resource metadata에서 제외한다. lint의 정적 엔진 목록은 16.15를 아직 모르지만 실제 서울 `DescribeDBEngineVersions`와 `DescribeOrderableDBInstanceOptions`가 PostgreSQL 16.15/db.t3.micro/gp3 조합을 확인했다. 실제 생성·available 확인은 별도의 검증이다.

복구는 성공한 template·manifest·secret 버전을 보존하는 데서 시작한다. DB·EBS·KMS·ECR를 자동 삭제하지 않는다. host 복구는 승인된 새 host 준비와 EIP 연결, DB 복구는 승인된 별도 RDS snapshot/PITR restore로 진행한다. 앱 이미지 rollback은 DB schema rollback이 아니다. template 파일만 수정하면 기존 호스트 파일이 자동 변경되지 않으므로 호스트 갱신은 고정 SSM 설치 document 또는 검토한 host 교체로 전달해야 한다.


## 실제 복구 완료와 현재 상태

2026-10-08 초기 runtime는 상위 VPC 권한 부족으로 rollback됐다. 원 handoff의 IAM 정책은 자동 변경하지 않았다. [최소 보완안](../../../aws-permission-handoff/proposed-2026-10-08/README.md)을 사용자가 승인하고 기존 관리자가 적용한 뒤 실제 JSON 일치를 확인했다. 실패 metadata 정리, 네트워크 선생성, 보존 EIP import를 완료했다. 이후 RDS 7일 backup 제한을 별도 승인한 1일 backup으로 복구했다. 현재 두 stack은 UPDATE_COMPLETE이며 EC2 `i-066c6e88c43ff81a5` running, RDS `capstone-prod` available, 기존 EIP `15.165.127.143` 연결 상태다. AWS 24개·호스트 13개 검증을 통과했다. 아래 복구 명령은 당시 절차 기록이며 현재 성공한 stack/DB에는 재사용하지 않는다.

`recover-runtime.py --approved-failed-stack-cleanup`은 승인된 exact 실패 stack metadata만 정리하고, 정책 불일치/다른 live resource가 있으면 중단한다. 삭제 완료 후 `change-set.py create runtime --network-only --name UNIQUE-NAME`으로 기존 runtime의 네트워크 15개만 생성·검토·실행하고 CREATE_COMPLETE를 기다린다. 신규 stack IMPORT에서 RoleArn/Tags 추가가 거부됐으므로 기존 실행 역할과 태그를 실제 stack에 먼저 확정하는 순서다. 그 다음 `import-retained-address.py --name UNIQUE-NAME`으로 기존 네트워크가 그대로인 템플릿에 EIP 한 개만 import한다. 기존 EIP allocation/public IP를 대조하고 release하지 않는다. IMPORT 완료 후 full runtime UPDATE를 검토한다. stack policy는 논리 ID 대신 리소스 유형을 조건으로 DB/Host/EIP의 삭제·교체를 차단하므로 선생성 단계에서도 적용할 수 있다. termination protection도 다시 활성화한다.

CREATE/UPDATE Change Set 실행은 `DisableRollback=True`로 신설 실패 리소스를 보존한다. IMPORT는 이 API 옵션을 지원하지 않으므로 옵션 없이 실행한다. 실패 시 terminal 상태·잔여 리소스·비용을 기록하고 자동 delete/replacement를 하지 않는다. 초기 bootstrap/runtime 생성에서는 기본 rollback이 사용됐다.

실패 Database 재시도는 별도 승인 후 `retry-uncreated-database.py --name REVIEWED-NAME --approved-uncreated-database-retry`로 실행한다. 실제 capstone-prod가 DBInstanceNotFound인지, 실패 논리 리소스가 CREATE_FAILED인지, Change Set과 로컬 1일 backup 템플릿이 일치하는지, 다른 교체/삭제가 없는지 확인한다. 실행 중에만 DB 유형 교체 차단을 예외 처리하고 Host/EIP 보호를 유지한다. terminal 상태에서는 성공/실패와 무관하게 원래 stack-policy.json을 다시 적용·조회해야 한다. 실제 DB가 생기면 이 도구는 재사용할 수 없다.

현재 runtime의 실제 template과 로컬 `runtime.json` 차이는 Host UserData에 포함된 압축 호스트 파일 묶음 한 곳이다. 실제 호스트는 동일 이름 SSM document version 2로 수정·검증했다. UserData를 맞추는 Change Set은 Host/EIPAssociation의 Conditional replacement를 표시해 실행하지 않고 삭제했다. 향후 기존 runtime 업데이트에서 이 차이를 무심코 적용하지 말고 전체 Change Set을 검토한다. 새 호스트를 처음 생성할 때는 로컬 템플릿의 수정된 파일 묶음을 사용한다.

읽기 검증은 `python3 infra/cloudformation/verify-aws.py`로 실행한다. 고정 호스트 검증은 `capstone-verify-infra` SSM command의 최종 Success/ResponseCode=0과 13개 boolean을 확인한다. 해당 document가 기존 SSM 로그 그룹에 상태를 기록하므로 native `CloudWatchOutputEnabled`는 false로 둔다. master bootstrap을 다시 실행하거나 임시 master 권한을 다시 열 필요는 없다.

2026-10-10 사용자 가비아 A 설정 후 DNS/EIP 일치와 HTTPS 발급·갱신 dry-run을 완료했다. 신규 capstone-verify-tls version1을 추가하는 Change Set 한 개만 적용했고 기존 resource/IAM/runtime은 유지했다. 고정 검증은 root 전용 키 권한·renewal timer 활성/다음 예약·Nginx·certificate 12개를 확인하며 키 값을 읽지 않는다. [실행 기록](../../../capstone_docs/development/dns-tls-execution-2026-10-10.md)을 따른다. 현재 HTTPS는 api-only 준비 페이지이며 앱 배포는 아직 없다. SNS 이메일은 PendingConfirmation이고, Budget은 실제 월 $40으로 유지됐다(승인 운영 한도는 $60; 관리자가 금액만 변경할 작업). Google OAuth·실제 BE/FE 이미지·P7/P8가 준비되기 전 G1/G2를 완료 처리하지 않는다.
