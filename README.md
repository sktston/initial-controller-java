# initial controller (java)
initial 플랫폼 서비스를 사용하기 위한 Java기반의 Issuer, Verifier, Holder controller 코드를 제공합니다.

## Steps to run
### 1. 사전준비 - 이니셜 웹 콘솔
테스트넷: https://dev-console.myinitial.io

#### Issuer 생성 및 설정
기관 생성하기
- 기관명: Issuer명 (변경되지 앖는 값, 신중하게 작성하세요)
- 도메인 접속 URL: https://issuer-controller.url (예시)
- Webhook URL: https://issuer-controller.url/webhooks (webhooks 변경하지 마세요)
- 도메인 접속 URL: https://issuer-controller.url/invitation-url (invitation-url 변경하지 마세요)
- 기관 구분: Issuer, Verifier 동시 선택
- AppType: Android, iOS 동시 선택
- App 노출: 미사용 (협의 후 사용)
- 기관 사용: 미사용 (협의 후 사용)

(아래는 샘플 데모를 수행하기 위함으로, 추후 새로 작성하여 사용하세요)

검증관리 - 검증 양식 생성 - 기본 증명 양식 - 마지막 페이지 - 모바일 가입증명(1.0)
- 검증 양식명: 모바일 가입 증명 검증 (예시)
- 양식 설명: 모바일 가입 증명 검증 (예시)
- 검증 항목 선택: person_name, mobile_num 등

검증관리 - 검증 양식 관리 - 모바일 가입 증명 검증 - 상세 보기
- 검증 양식 ID (`verifTplId`): `0012d683-bdb0-4050-ac85-ae37e59bad09` (예시)

발행관리 - 발행 양식 생성 - 샘플 학위증명(1.0)
- 양식설명: 샘플 증명서 (예시)
- 기본 발행 항목: 변경 불가 (협의 후 새로운 기본양식 추가 가능)
- 폐기지원여부: 예

발행관리 - 발행 양식 관리 - 샘플 학위증명(1.0)
- 증명서 발행 설정: 발행 시작
- 증명서 ID (`CredDefId`): `Qr7Yo4sPs7cXiiVbEYwGsJ:3:CL:1618984624:1ee53b6d-7d8c-461e-910a-623302dc854a` (예시)

기관관리 - 기관 정보
- `Access Token`: `514ac4f8-e0da-43c9-910d-4894279909b2` (예시)
- Webhook URL: https://issuer-controller.url/webhooks (**서버 주소 및 webhooks 다시 한번 확인**)

#### Holder 생성 및 설정 (Issuer 동작 확인 위함)
기관 생성하기
- 기관명: Holder명 (테스트 용도)
- 도메인 접속 URL: https://holder-controller.url (예시)
- Webhook URL: https://holder-controller.url/webhooks (webhooks 변경하지 마세요)
- 도메인 접속 URL: https://holder-controller.url/invitation-url (invitation-url 변경하지 마세요)
- 기관 구분: Verifier 선택
- AppType: Android, iOS 동시 선택
- App 노출: 미사용
- 기관 사용: 미사용

기관관리 - 기관 정보
- `Access Token`: `3a0ece13-dd04-419d-b3ea-f12b52e297d7` (예시)
- Webhook URL: https://holder-controller.url/webhooks (**서버 주소 및 webhooks 다시 한번 확인**)

### 2. properties 설정 - 본 repository 코드
`src/main/resources/`

#### application-issuer.properties
server.port = 8040 \
platformUrl = https://dev-console.myinitial.io (고정) \
accessToken = issuer의 `Access Token` \
credDefId = 작성한 issuer의 `CredDefId` \
verifTplId = 작성한 issuer의 `verifTplId`

#### application-holder.properties
server.port = 8041 \
platformUrl = https://dev-console.myinitial.io (고정) \
accessToken = holder의 `Access Token` \
issuerControllerUrl = https://issuer-controller.url
issuerCredDefId = 작성한 issuer의 `CredDefId`

### 3. issuer 및 holder 실행 - 각 terminal
#### issuer 실행 (issuer terminal)
```
./gradlew issuer
```
또는 web view 로직이 들어간 데모를 수행하려는 경우 `./gradlew issuer_webview` \
또는 revocation 로직이 들어간 데모를 수행하려는 경우 `./gradlew issuer_revoke`

#### 정상 구동 시 메시지 (issuer terminal)
```
[GlobalService.java]initializeAfterStartup(61) : Controller is ready
```

#### holder 실행 (holder terminal)
```
./gradlew holder
```

#### issuer 의 증명서가 정상 발급 된 경우 메시지 (holder terminal)
```
[GlobalService.java]handleEvent(97) : - Case (topic:issue_credential, state:credential_acked) -> credential received successfully
```

## Work flow
### Initialization
Issuer는 accessToken, credDefId, verifTplId 가 valid 한 지 확인 하고 대기함. \
Holder는 accessToken이 valid 한 지 확인 후, 샘플 모바일 가입증명을 발급 받고, 아래 과정 진행.

### Connection
Holder가 https://issuer-controller.url/invitation-url 호출부터 시작
| Issuer API | Holder API | Issuer webhook (topic, state) | Holder webhook (topic, state) |
|---|---|---|---|
| POST /connections/create-invitation |  |  |  |
|  | POST /connections/receive-invitation |  | connections, invitation |
|  |  | connections, request | connections, request |
|  |  | connections, response | connections, response |
|  |  | connections, active | connections, active |

### Presentation
Holder가 (connections, active) 시점에 credential proposal을 보냄
| Issuer API | Holder API | Issuer webhook (topic, state, *msg_type) | Holder webhook (topic, state, *msg_type) |
|---|---|---|---|
|  | POST /issue-credential/send-proposal | issue_credential, proposal_received | issue_credential, proposal_sent |
| POST /connections/{conn_id}/send-message |  |  | basicmessages, received, *initial_agreement |
|  | POST /connections/{conn_id}/send-message | basicmessages, received, *initial_agreement_decision |  |
| POST /present-proof/send-verification-request |  | present_proof, request_sent | present_proof, request_received |
|  | GET /present-proof/records/{presExId}/credentials |  |  |
|  | POST /present-proof/records/{presExId}/send-presentation | present_proof, presentation_received | present_proof, presentation_sent |
|  |  | present_proof, verified | present_proof, presentation_acked |

Issuer는 (present_proof, verified) 시점에 webhook 메시지를 getPresentationResult 하여 요구한 정보 획득

### (Optional) Web View
Issuer가 Presentation의 정보로 발행 가능한 증명서를 한정하기 어려운 경우, 추가 정보 획득을 위함
| Issuer API | Holder API | Issuer webhook (topic, state, *msg_type) | Holder webhook (topic, state, *msg_type) |
|---|---|---|---|
| POST /connections/{conn_id}/send-message |  |  | basicmessages, received, *initial_web_view |

Holder는 Issuer가 제공한 web view 페이지를 보여주고 사용자가 item 하나를 선택함 \
본 데모에서는 item 하나를 선택하여 Issuer의 POST https://issuer-controller.url/web-view/{presExId} 를 호출한다는 가정하에 동작함

### Issue Credential
Issuer는 받은 정보를 기반으로 DB를 qeury하여 증명서를 작성하여 발급함
| Issuer API | Holder API | Issuer webhook (topic, state) | Holder webhook (topic, state) |
|---|---|---|---|
| POST /issue-credential/send |  | issue_credential, offer_sent | issue_credential, offer_received |
|  | POST /issue-credential/records/{credExId}/send-request | issue_credential, request_received | issue_credential, request_sent |
|  |  | issue_credential, credential_issued | issue_credential, credential_received |
|  |  | issuer_cred_rev, issued |  |
|  |  | issue_credential, credential_acked | issue_credential, credential_acked |

발급한 증명서를 폐기(revocation)하기 위해, \
Issuer는 (issue_credential, credential_acked) 시점에 webhook 메시지를 확인하여 credential_exchange_id 를 DB에 기록해 두어야 함

### (Optional) Revocation
| Issuer API | Holder API | Issuer webhook (topic, state) | Holder webhook (topic, state) |
|---|---|---|---|
| POST /revocation/revoke |  | issuer_cred_rev, revoked |  |

Revoke된 credential은 Issuer가 (present_proof, verified) 시점에, webhook 메시지를 getPresentationResult 하는 과정에서 verified 가 false 임

## Production
뱐경 해야 할 항목만 정리

### 1. 사전준비
상용: https://console.myinitial.io

### 2. properties 설정
#### application-issuer-prod.properties
platformUrl = https://console.myinitial.io (고정)

#### application-holder-prod.properties
platformUrl = https://console.myinitial.io (고정)

### 3. issuer 및 holder 실행
#### issuer 실행
```
./gradlew issuer_prod
```

#### holder 실행
```
./gradlew holder_prod
```
