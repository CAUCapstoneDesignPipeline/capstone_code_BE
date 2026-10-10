# Reviewed release manifests

승인된 계약 SHA와 실제 발행된 BE/FE image digest가 준비된 뒤 `<release-id>.json`을 추가한다. 샘플 digest나 가짜 OAuth 값으로 실행 가능한 manifest를 만들지 않는다. 필드는 `../host/manifest.py`의 validator를 따른다. 같은 release ID를 다른 내용에 재사용하지 않는다. 현재 `notes-web`은 실제 FE 인증 연동 전이므로 거부한다.
