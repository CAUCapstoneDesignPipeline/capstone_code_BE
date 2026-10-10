# Release manifests

GitHub App이나 docs 저장소·계약 SHA 없이 BE/FE 저장소만으로 발행·배포한다. `publish-image.yml`이 만든 실제 `image-metadata.json` 두 개를 받은 뒤 `deploy/pipeline/make-release.py`로 `<release-id>.json`을 준비한다. source SHA·digest·amd64·protocol 4와 발행 시 실행 검증 기록을 사용한다. 샘플 digest·가짜 OAuth 값을 넣지 않는다.

```bash
python3 deploy/pipeline/make-release.py \
  --be-metadata /actual/path/be/image-metadata.json \
  --web-metadata /actual/path/web/image-metadata.json \
  --release-id ACTUAL_RELEASE_ID --config-revision api-only-v2 \
  --flyway-before ACTUAL_CURRENT_VERSION --flyway-after ACTUAL_TARGET_VERSION \
  --compatible-with ACTUAL_COMPATIBLE_VERSION \
  --db-password-version ACTUAL_VERSION --jwt-secret-version ACTUAL_VERSION
```

위 대문자 값은 실제 조회값으로 교체한다. 첫 빈 DB의 before는 `empty`이며 V1·V2가 있는 현재 source의 after는 `2`다. `compatible-with`는 대상 이미지가 사용할 수 있는 실제 schema 버전을 명시하며 DB를 되돌리는 값이 아니다. 기존 schema 변경은 available snapshot의 실제 evidence 파일을 `--backup-evidence`로 전달해야 한다. 버전은 Parameter Store metadata에서만 조회하고 비밀값을 읽거나 입력하지 않는다.

사용자가 manifest를 검토·main에 병합한 뒤 BE의 `Deploy production`을 manifest 경로와 현재 release ID로 실행한다. 첫 배포만 expected_release=`none`이다. main·enable flag·Son2kwon 환경 승인과 고정 SSM document version 4가 필요하다. 호스트 protocol 4 설치와 `production-config.example.json`의 실제 root 설정이 선행되어야 한다. 실제 ECR 이미지가 없으면 manifest를 생성하지 않는다.

`Rollback production`은 보관된 바로 이전 성공 manifest만 사용한다. 이미 main에 있는 그 파일의 경로와 현재 release ID를 입력한다. host의 실제 Flyway schema가 호환 목록에 있어야 한다. 이미지 재빌드·DB 역마이그레이션·clean/repair·secret 과거값 복원은 하지 않는다. 첫 배포에는 이전 성공 버전이 없어 롤백이 불가능하다.

배포 실패나 SSM 대기 시간 초과 시 새 실행을 반복하지 않는다. command ID의 종료 상태와 pending journal을 조사한다. journal이 남았거나 schema가 변경됐으면 maintenance를 유지하고 개별 복구안을 검토한다. `PRODUCTION_DEPLOY_ENABLED=false`로 추가 운영 실행을 차단할 수 있다.

현재는 api-only만 허용하고 AI와 개발 토큰은 비활성이다. 실제 OAuth·FE 인증이 없으므로 사용자 개통 완료로 처리하지 않는다.
