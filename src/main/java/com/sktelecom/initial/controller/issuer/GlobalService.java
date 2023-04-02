package com.sktelecom.initial.controller.issuer;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.sktelecom.initial.controller.utils.HttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;

import static com.sktelecom.initial.controller.utils.Common.*;

@RequiredArgsConstructor
@Service
@Slf4j
public class GlobalService {
    private final HttpClient client = new HttpClient();

    @Value("${agentApiUrl}")
    private String agentApiUrl; // agent service api url

    @Value("${accessToken}")
    private String accessToken; // controller access token

    @Value("${credDefId}")
    private String credDefId; // credential definition identifier

    @Value("${mobileVerifTplId}")
    private String mobileVerifTplId; // verification template identifier

    @Value("${toeicVerifTplId}")
    private String toeicVerifTplId; // Toeic verification template identifier

    @Value("${webViewUrl}")
    private String webViewUrl; // web view form url

    private String mobile_credDefId = "TmisnEAGBPeVVDjtAXPdYt:3:CL:0:v01"; // credential definition identifier

    String orgName;
    String orgImageUrl;
    String publicDid;
    String credExId; //problem report를 위해 전역으로 선언
    boolean webhookUrlIsValid = false;

    LinkedHashMap<String, String> connIdToCredExId = new LinkedHashMap<>(); // cache to keep credential issuing flow
    LinkedHashMap<String, String> attrs = new LinkedHashMap<>(); // cache to keep credential issuing flow
    LinkedHashMap<String, String> selfAttrs = new LinkedHashMap<>(); // cache to keep credential issuing flow

    // for revocation example
    static boolean enableRevoke = Boolean.parseBoolean(System.getenv().getOrDefault("ENABLE_REVOKE", "false"));

    // for web view example
    static boolean enableWebView = Boolean.parseBoolean(System.getenv().getOrDefault("ENABLE_WEB_VIEW", "false"));

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterStartup() {
        provisionController();

        log.info("Controller configurations");
        log.info("------------------------------");
        log.info("- organization name: " + orgName);
        log.info("- organization imageUrl: " + orgImageUrl);
        log.info("- public did: " + publicDid);
        log.info("- credential definition id: " + credDefId);
        log.info("- verification template id: " + mobileVerifTplId);
        log.info("- controller access token: " + accessToken);
        log.info("------------------------------");
        log.info("Controller is ready");
    }

    public void handleEvent(String body) {
        if (!webhookUrlIsValid)
            webhookUrlIsValid = true;

        String topic = JsonPath.read(body, "$.topic");
        String state = null;
        try {
            state = JsonPath.read(body, "$.state");
        } catch (PathNotFoundException e) {}
        log.info("handleEvent >>> topic:" + topic + ", state:" + state + ", body:" + body);

        switch(topic) {
            case "issue_credential":
                if (state == null) {
                    log.warn("- Case (topic:" + topic + ", ProblemReport) -> Print Error Message");
                    String errorMsg = JsonPath.read(body, "$.error_msg");
                    log.warn("  - error_msg: " + errorMsg);
                }
                // 1. holder 가 credential 을 요청함 -> 모바일 가입증명 검증 요청
                else if (state.equals("proposal_received")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> checkCredentialProposal && sendPresentationRequest");
                    String connectionId = JsonPath.read(body, "$.connection_id");
                    //String credExId = JsonPath.read(body, "$.credential_exchange_id");
                    credExId = JsonPath.read(body, "$.credential_exchange_id");
                    String credentialProposal = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.credential_proposal_dict")).jsonString();
                    if(checkCredentialProposal(connectionId, credExId, credentialProposal)) {
                        sendMobilePresentationRequest(connectionId);
                        //sendManualMobilePresentationRequest(connectionId); //JJ self attested test
                    }
                }
                // 4. holder 가 증명서를 정상 저장하였음 -> 완료 (revocation 은 아래 코드 참조)
                else if (state.equals("credential_acked")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> credential issued successfully");

                    // TODO: should store credential_exchange_id to revoke this credential
                    // connIdToCredExId is simple example for this
                    if (enableRevoke) {
                        String connectionId = JsonPath.read(body, "$.connection_id");
                        String credExId = connIdToCredExId.get(connectionId);
                        revokeCredential(credExId);
                    }
                }
                break;
            case "basicmessages":
                String content = JsonPath.read(body, "$.content");
                String type = getTypeFromBasicMessage(content);
                // 2. holder 가 개인정보이용 동의를 보냄 -> 동의 내용 저장
                if (type != null && type.equals("initial_agreement_decision")) {
                    if (isAgreementAgreed(content)) {
                        log.info("- Case (topic:" + topic + ", state:" + state + ", type:" + type + ") -> AgreementAgreed");
                        // TODO: store agreement decision
                    }
                }
                else
                    log.warn("- Warning: Unexpected type:" + type);
                break;
            case "present_proof":
                if (state == null) {
                    log.warn("- Case (topic:" + topic + ", ProblemReport) -> Print Error Message");
                    String errorMsg = JsonPath.read(body, "$.error_msg");
                    log.warn("  - error_msg: " + errorMsg);
                }
                else if (state.equals("proposal_received")) {
                    String connectionId = JsonPath.read(body, "$.connection_id");
                    sendToeicPresentationRequest(connectionId);
                }
                // 3. holder 가 보낸 모바일 가입증명 검증 완료
                else if (state.equals("verified")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> getPresentationResult");
                    LinkedHashMap<String, String> attrs = getPresentationResult(body);
                    String name = getPresentationName(body);
                    //if (name.equals("모바일 가입증명 검증")) {
                    if (name != null) {
                        if (enableWebView) {
                            // 3-1. 검증 값 정보로 발행할 증명서가 한정되지 않는 경우 추가 정보 요구
                            log.info("Web View enabled -> sendWebView");
                            String connectionId = JsonPath.read(body, "$.connection_id");
                            
                            try {
                                TimeUnit.SECONDS.sleep(1);
                                log.info("time delay 1s");
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            sendWebView(connectionId, attrs, body);
                        } else {
                            // 3-2. 검증 값 정보 만으로 발행할 증명서가 한정되는 경우 증명서 바로 발행
                            log.info("Web View is not used -> sendCredentialOffer");
                            String connectionId = JsonPath.read(body, "$.connection_id");
                            //sendCredentialOfferConnectionId(connectionId, attrs, null, null);
                            sendCredentialOffer(connectionId, attrs, null, null);

                        }
                    }
                }
                break;
            case "problem_report":
                log.warn("- Case (topic:" + topic + ") -> Print body");
                log.warn("  - body:" + body);
                break;
            case "connections":
            case "revocation_registry":
            case "issuer_cred_rev":
                break;
            default:
                log.warn("- Warning Unexpected topic:" + topic);
        }
    }

    void provisionController() {
        log.info("TEST - Create invitation-url");
        String invitationUrl = createInvitationUrl();
        if (invitationUrl == null) {
            log.info("- FAILED: Check if accessToken is valid - " + accessToken);
            System.exit(0);
        }
        String invitation = parseInvitationUrl(invitationUrl);
        publicDid = JsonPath.read(invitation, "$.did");
        orgName = JsonPath.read(invitation, "$.label");
        orgImageUrl = JsonPath.read(invitation, "$.imageUrl");
        log.info("- SUCCESS");

        if (!credDefId.equals("")) {
            log.info("TEST - Check if credential definition is valid");
            String response = client.requestGET(agentApiUrl + "/credential-definitions/" + credDefId, accessToken);
            log.info("response: " + response);
            LinkedHashMap<String, Object> credDef = JsonPath.read(response, "$.credential_definition");
            if (credDef == null) {
                log.info("- FAILED: " + credDefId + " does not exists - Check if it is valid");
                System.exit(0);
            }
            log.info("- SUCCESS : " + credDefId + " exists");

            log.info("TEST - Check if this controller owns the credential definition");
            String params = "?cred_def_id=" + credDefId;
            response = client.requestGET(agentApiUrl + "/credential-definitions/created" + params, accessToken);
            log.info("response: " + response);
            ArrayList<String> credDefIds = JsonPath.read(response, "$.credential_definition_ids");
            if (credDefIds.isEmpty())
                log.info("- NO: This controller can not issue with " + credDefId);
            else
                log.info("- YES : This controller can issue with " + credDefId);
        }

        if (!mobileVerifTplId.equals("")) {
            log.info("TEST - Check if verification template is valid");
            String response = client.requestGET(agentApiUrl + "/verification-templates/" + mobileVerifTplId, accessToken);
            log.info("response: " + response);
            LinkedHashMap<String, Object> verifTpl = JsonPath.read(response, "$.verification_template");
            if (verifTpl == null) {
                log.info("- FAILED: " + mobileVerifTplId + " does not exists - Check if it is valid");
                System.exit(0);
            }
            log.info("- SUCCESS : " + mobileVerifTplId + " exists");
        }

        log.info("TEST - Check if webhook url (in console) is valid");
        // create non-public invitation to receive webhook message
        client.requestPOST(agentApiUrl + "/connections/create-invitation", accessToken, "{}");
        try {
            Thread.sleep(1000); // wait to receive webhook message
        } catch (InterruptedException e) {}
        if (!webhookUrlIsValid) {
            log.info("- FAILED: webhook message is not received - Check if it is valid in console configuration");
            System.exit(0);
        }
        log.info("- SUCCESS");

    }

    public String createInvitationUrl() {
        String params = "?public=true";
        String response = client.requestPOST(agentApiUrl + "/connections/create-invitation" + params, accessToken, "{}");
        log.info("response: " + response);
        try {
            return JsonPath.read(response, "$.invitation_url");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String createOobInvitationUrl() {
        String body = JsonPath.parse("{" +
                "  handshake_protocols: ['connections/1.0']," +
                "  use_public_did: true" +
                "}").jsonString();
        String response = client.requestPOST(agentApiUrl + "/out-of-band/create-invitation", accessToken, body);
        log.info("response: " + response);
        try {
            return JsonPath.read(response, "$.invitation_url");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void sendCredProblemReport(String credExId, String description) {
        String body = JsonPath.parse("{" +
                "  description: '" + description + "'" +
                "}").jsonString();
        String response = client.requestPOST(agentApiUrl + "/issue-credential/records/" + credExId + "/problem-report", accessToken, body);
        log.info("response: " + response);
    }

    public void sendPresProblemReport(String presExId, String description) {
        String body = JsonPath.parse("{" +
                "  description: '" + description + "'" +
                "}").jsonString();
        String response = client.requestPOST(agentApiUrl + "/present-proof/records/" + presExId + "/problem-report", accessToken, body);
        log.info("response: " + response);
    }

    public boolean checkCredentialProposal(String connectionId, String credExId, String credentialProposal) {
        try {
            String requestedCredDefId = JsonPath.read(credentialProposal, "$.cred_def_id");
            if (requestedCredDefId.equals(credDefId)){
                connIdToCredExId.put(connectionId, credExId);
                return true;
            }
            log.warn("This issuer can issue credDefId:" + credDefId);
            log.warn("But, requested credDefId is " + requestedCredDefId + " -> problemReport");
            sendCredProblemReport(credExId, "본 기관은 요청한 증명서 (credDefId:" + requestedCredDefId + ") 를 발급하지 않습니다");
        } catch (PathNotFoundException e) {
            log.warn("Requested credDefId does not exist -> problemReport");
            sendCredProblemReport(credExId, "증명서 (credDefId) 가 지정되지 않았습니다");
        }
        return false;
    }

    public void sendMobilePresentationRequest(String connectionId) {
        // sample agreement
        String agreement = JsonPath.parse("{" +
                "  type: 'initial_agreement',"+
                "  content: [" +
                "    {" +
                "      sequence: 1," +
                "      title: '개인정보 수집 및 이용 동의서'," +
                "      is_mandatory: 'true'," +
                "      terms_id: 'person'," +
                "      terms_ver: '1.0'," +
                "      agreement: 'initial서비스(이하“서비스”라 한다)와 관련하여, 본인은 동의 내용을 숙지하였으며, 이에 따라 본인의 개인정보를 귀사(SK텔레콤주식회사)가 수집 및 이용하는 것에 대해 동의 합니다. 본 동의는 서비스의 본질적 기능 제공을 위한 개인정보 수집/이용에 대한 동의로서, 동의를 하는 경우에만 서비스 이용이 가능합니다. 법령에 따른 개인정보의 수집/이용, 계약의 이행/편익 제공을 위한 개인정보 취급 위탁 및 개인정보 취급과 관련된 일반 사항은 서비스의 개인정보 처리 방침에 따릅니다.'," +
                "      condition: [" +
                "        {" +
                "          sub_title: '수집 항목'," +
                "          target: '이름,생년월일'" +
                "        }," +
                "        {" +
                "          sub_title: '수집 및 이용목적'," +
                "          target: '서비스 이용에 따른 본인확인'" +
                "        }," +
                "        {" +
                "          sub_title: '이용기간 및 보유/파기'," +
                "          target: '1년'" +
                "        }" +
                "      ]" +
                "    }," +
                "    {" +
                "      sequence: 2," +
                "      title: '위치정보 수집 및 이용 동의서'," +
                "      is_mandatory: 'true'," +
                "      terms_id: 'location'," +
                "      terms_ver: '1.0'," +
                "      agreement: '이 약관은 이니셜(SK텔레콤)(이하“회사”)가 제공하는 위치 정보사업 또는 위치기반 서비스 사업과 관련하여 회사와 개인 위치 정보주체와의 권리, 의무 및 책임사항, 기타 필요한 사항을 규정함을 목적으로 합니다.'," +
                "      condition: [" +
                "        {" +
                "          sub_title: '위치정보 수집 방법'," +
                "          target: 'GPS칩'" +
                "        }," +
                "        {" +
                "          sub_title: '위치정보 이용/제공'," +
                "          target: '이 약관에 명시되지 않은 사항은 위치정보의 보호 및 이용 등에 관한 법률, 정보통신망 이용촉진 및 정보보호 등에 관한 법률, 전기통신기본법, 전기통신사업법 등 관계법령과 회사의 이용약관 및 개인정보취급방침, 회사가 별도로 정한 지침 등에 의합니다.'" +
                "        }," +
                "        {" +
                "          sub_title: '수집목적'," +
                "          target: '현재의 위치를 기반으로 하여 주변 매장의 위치 등의 정보를 제공하는 서비스'" +
                "        }," +
                "        {" +
                "          sub_title: '위치정보 보유기간'," +
                "          target: '1년'" +
                "        }" +
                "      ]" +
                "    }," +
                "    {" +
                "      sequence: 3," +
                "      title: '제3자 정보제공 동의서'," +
                "      is_mandatory: 'true'," +
                "      terms_id: '3rdparty'," +
                "      terms_ver: '1.0'," +
                "      agreement: 'initial서비스(이하“서비스”라 한다)와 관련하여, 본인은 동의 내용을 숙지하였으며, 이에 따라 본인의 개인정보를 귀사(이슈어)가 수집한 개인정보를 아래와 같이 제3자에게 제공하는 것에 대해 동의 합니다. 고객은 개인정보의 제3자 제공에 대한 동의를 거부할 권리가 있으며, 동의를 거부할 시 받는 별도의 불이익은 없습니다. 단, 서비스 이용이 불가능하거나, 서비스 이용 목적에 따른 서비스 제공에 제한이 따르게 됩니다.'," +
                "      condition: [" +
                "        {" +
                "          sub_title: '제공하는 자'," +
                "          target: '발급기관'" +
                "        }," +
                "        {" +
                "          sub_title: '제공받는 자'," +
                "          target: '이니셜(SK텔레콤)'" +
                "        }," +
                "        {" +
                "          sub_title: '제공받는 항목'," +
                "          target: '생년월일,시험일,성명(영문),만료일,성명(한글),수험번호,듣기점수,읽기점수,총점'" +
                "        }," +
                "        {" +
                "          sub_title: '수집 및 이용목적'," +
                "          target: '모바일 전자증명서 발급'" +
                "        }," +
                "        {" +
                "          sub_title: '보유 및 이용기간'," +
                "          target: '모바일 전자증명서 발급을 위해 서버에 임시 저장하였다가, 증명서 발행 후 즉시 삭제(단, 고객 단말기 내부 저장영역에 증명서 형태로 저장/보관)'" +
                "        }" +
                "      ]" +
                "    }" +
                "  ]"+
                "}").jsonString();

        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId + "'," +
                "  verification_template_id: '" + mobileVerifTplId + "'," +
                "  agreement: " + agreement +
                "}").jsonString();
        String response = client.requestPOST(agentApiUrl + "/present-proof/send-verification-request", accessToken, body);
        log.info("response: " + response);
    }

    public void sendManualMobilePresentationRequest(String connectionId) {
        long curUnixTime = System.currentTimeMillis() / 1000L;

        // sample agreement
        String agreement = JsonPath.parse("{\n" +
                "\"type\": \"initial_agreement\",\n" +
                "\"content\": [{\n" +
                "\"sequence\": 1,\n" +
                "\"title\": \"개인정보 수집 및 이용 동의서\",\n" +
                "\"is_mandatory\": \"true\",\n" +
                "\"terms_id\": \"person\",\n" +
                "\"terms_ver\": \"1.0\",\n" +
                "\"agreement\": \"Initial 서비스(이하 “서비스”라한다)와 관련하여, 본인은 동의내용을 숙지하였으며, 이에따라 본인의 개인정보를 귀사(SK텔레콤주식회사)가 수집 및 이용하는 것에 대해 동의합니다. 본동의는 서비스의 본질적 기능제공을 위한 개인정보 수집/이용에 대한 동의로서, 동의를 하는경우에만 서비스 이용이 가능합니다.법령에따른개인정보의수집/이용, 계약의이행/편익제공을위한개인정보취급위탁및개인정보취급과관련된일반사항은서비스의개인정보처리방침에따릅니다.\",\n" +
                "\"condition\": [{\n" +
                "\"sub_title\": \"수집 항목\",\n" +
                "\"target\": \"이름,생년월일\"\n" +
                "},\n" +
                "{\n" +
                "\"sub_title\": \"수집및이용목적\",\n" +
                "\"target\": \"서비스이용에따른본인확인\"\n" +
                "},\n" +
                "{\n" +
                "\"sub_title\": \"이용기간및보유/파기\",\n" +
                "\"target\": \"1년\"\n" +
                "},\n" +
                "{\n" +
                "\"sub_title\": \"기타 정보\",\n" +
                "\"target\": \"기타 내용\"\n" +
                "}\n" +
                "]\n" +
                "},\n" +
                "{\n" +
                "\"sequence\": 2,\n" +
                "\"title\": \"위치정보 수집 및 이용 동의서\",\n" +
                "\"is_mandatory\": \"true\",\n" +
                "\"terms_id\": \"location\",\n" +
                "\"terms_ver\": \"1.0\",\n" +
                "\"agreement\": \"이 약관은 이니셜(SK텔레콤)(이하 “회사”)가 제공하는 위치정보사업 또는 위치기반서비스사업과 관련하여 회사와 개인위치정보주체와의 권리, 의무 및 책임사항, 기타 필요한 사항을 규정함을 목적으로 합니다.\",\n" +
                "\"condition\": [{\n" +
                "\"sub_title\": \"위치정보 수집 방법\",\n" +
                "\"target\": \"GPS칩\"\n" +
                "},\n" +
                "{\n" +
                "\"sub_title\": \"위치정보 이용/제공\",\n" +
                "\"target\": \"이 약관에 명시되지 않은 사항은 위치정보의 보호 및 이용 등에 관한 법률, 정보통신망 이용촉진 및 정보보호 등에 관한 법률, 전기통신기본법, 전기통신사업법 등 관계법령과 회사의 이용약관 및 개인정보취급방침, 회사가 별도로 정한 지침 등에 의합니다.\"\n" +
                "},\n" +
                "{\n" +
                "\"sub_title\": \"수집목적\",\n" +
                "\"target\": \"현재의 위치를 기반으로 하여 주변 매장의 위치 등의 정보를 제공하는 서비스\"\n" +
                "},\n" +
                "{\n" +
                "\"sub_title\": \"위치정보 보유기간\",\n" +
                "\"target\": \"1년\"\n" +
                "}\n" +
                "]\n" +
                "},\n" +
                "{\n" +
                "\"sequence\": 3,\n" +
                "\"title\": \"테스트 수집 및 이용 동의서\",\n" +
                "\"is_mandatory\": \"true\",\n" +
                "\"terms_id\": \"test\",\n" +
                "\"terms_ver\": \"1.0\",\n" +
                "\"agreement\": \"이 약관은 이니셜(SK텔레콤)(이하 “회사”)가 제공하는 위치정보사업 또는 위치기반서비스사업과 관련하여 회사와 개인위치정보주체와의 권리, 의무 및 책임사항, 기타 필요한 사항을 규정함을 목적으로 합니다.\",\n" +
                "\"condition\": [{\n" +
                "\"sub_title\": \"위치정보 수집 방법\",\n" +
                "\"target\": \"GPS칩\"\n" +
                "},\n" +
                "{\n" +
                "\"sub_title\": \"위치정보 이용/제공\",\n" +
                "\"target\": \"이 약관에 명시되지 않은 사항은 위치정보의 보호 및 이용 등에 관한 법률, 정보통신망 이용촉진 및 정보보호 등에 관한 법률, 전기통신기본법, 전기통신사업법 등 관계법령과 회사의 이용약관 및 개인정보취급방침, 회사가 별도>로 정한 지침 등에 의합니다.\"\n" +
                "},\n" +
                "{\n" +
                "\"sub_title\": \"수집목적\",\n" +
                "\"target\": \"현재의 위치를 기반으로 하여 주변 매장의 위치 등의 정보를 제공하는 서비스\"\n" +
                "},\n" +
                "{\n" +
                "\"sub_title\": \"위치정보 보유기간\",\n" +
                "\"target\": \"1년\"\n" +
                "}\n" +
                "]\n" +
                "},\n" +
                "{\n" +
                "\"sequence\": 4,\n" +
                "\"title\": \"제3자 정보제공 동의서\",\n" +
                "\"is_mandatory\": \"true\",\n" +
                "\"terms_id\": \"3rdparty\",\n" +
                "\"terms_ver\": \"1.0\",\n" +
                "\"agreement\": \" initial 서비스(이하 “서비스”라한다)와관련하여, 본인은동의내용을숙지하였으며, 이에따라본인의개인정보를귀사(이슈어)가수집한개인정보를아래와같이제3자에게제공하는것에대해동의합니다. 고객은개인정보의제3자제공에대한동의를거부할권리가있으며, 동의를거부할받는별도의불이익은없습니다. 단, 서비스이용불가능하거나, 서비스이용목적에따른서비스제공에제한이따르게됩니다.\",\n" +
                "\"condition\": [{\n" +
                "\"sub_title\": \"제공하는자\",\n" +
                "\"target\": \"발급기관\"\n" +
                "},\n" +
                "{\n" +
                "\"sub_title\": \"제공받는자\",\n" +
                "\"target\": \"이니셜(SK텔레콤)\"\n" +
                "},\n" +
                "{\n" +
                "\"sub_title\": \"제공받는 항목\",\n" +
                "\"target\": \"제공항목(생년월일,시험일,성명(영문),만료일,성명(한글),수험번호,듣기점수,읽기점수,총점)\"\n" +
                "},\n" +
                "{\n" +
                "\"sub_title\": \"수집 및 이용목적\",\n" +
                "\"target\": \"모바일전자증명서발행\"\n" +
                "},\n" +
                "{\n" +
                "\"sub_title\": \"보유 및 이용기간\",\n" +
                "\"target\": \"모바일 전자증명서 발급을 위해 서버에 임시 저장하였다가, 증명서 발행 후 즉시 삭제(단, 고객 단말기 내부 저장영역에 증명서 형태로 저장/보관)\"\n" +
                "}\n" +
                "]\n" +
                "}\n" +
                "]\n" +
                "\n" +
                "}").jsonString();

        String body = JsonPath.parse("{" +
                "  comment: '" + agreement + "'," +
                "  connection_id: '" + connectionId + "'," +
                "  proof_request: {" +
                "    name: '모바일 가입증명 검증'," +
                "    version: '1.0'," +
                "    requested_attributes: {" +
                "      person_name: {" +
                "        name: 'person_name'," +
                "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "        restrictions: [ {cred_def_id: '" + mobile_credDefId + "'} ]" +
                "      }," +
                "      date_of_birth: {" +
                "        name: 'date_of_birth'," +
                "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "        restrictions: [ {cred_def_id: '" + mobile_credDefId + "'} ]" +
                "      }," +
                "      mobile_num: {" +
                "        name: 'mobile_num'," +
                "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "        restrictions: [ {cred_def_id: '" + mobile_credDefId + "'} ]" +
                "      }" +
                "    }," +
                "    requested_predicates: {" +
                "    }" +
                "  }" +
                "}").jsonString();
        String response = client.requestPOST(agentApiUrl + "/present-proof/send-request", accessToken, body);
        log.info("response: " + response);
    }

    public void sendToeicPresentationRequest(String connectionId) {
        // sample agreement
        String agreement = JsonPath.parse("{" +
                "  type: 'initial_agreement',"+
                "  content: [" +
                "    {" +
                "      sequence: 1," +
                "      title: '개인정보 수집 및 이용 동의서'," +
                "      is_mandatory: 'true'," +
                "      terms_id: 'person'," +
                "      terms_ver: '1.0'," +
                "      agreement: 'initial서비스(이하“서비스”라 한다)와 관련하여, 본인은 동의 내용을 숙지하였으며, 이에 따라 본인의 개인정보를 귀사(SK텔레콤주식회사)가 수집 및 이용하는 것에 대해 동의 합니다. 본 동의는 서비스의 본질적 기능 제공을 위한 개인정보 수집/이용에 대한 동의로서, 동의를 하는 경우에만 서비스 이용이 가능합니다. 법령에 따른 개인정보의 수집/이용, 계약의 이행/편익 제공을 위한 개인정보 취급 위탁 및 개인정보 취급과 관련된 일반 사항은 서비스의 개인정보 처리 방침에 따릅니다.'," +
                "      condition: [" +
                "        {" +
                "          sub_title: '수집 항목'," +
                "          target: '이름,생년월일'" +
                "        }," +
                "        {" +
                "          sub_title: '수집 및 이용목적'," +
                "          target: '서비스 이용에 따른 본인확인'" +
                "        }," +
                "        {" +
                "          sub_title: '이용기간 및 보유/파기'," +
                "          target: '1년'" +
                "        }" +
                "      ]" +
                "    }," +
                "    {" +
                "      sequence: 2," +
                "      title: '위치정보 수집 및 이용 동의서'," +
                "      is_mandatory: 'true'," +
                "      terms_id: 'location'," +
                "      terms_ver: '1.0'," +
                "      agreement: '이 약관은 이니셜(SK텔레콤)(이하“회사”)가 제공하는 위치 정보사업 또는 위치기반 서비스 사업과 관련하여 회사와 개인 위치 정보주체와의 권리, 의무 및 책임사항, 기타 필요한 사항을 규정함을 목적으로 합니다.'," +
                "      condition: [" +
                "        {" +
                "          sub_title: '위치정보 수집 방법'," +
                "          target: 'GPS칩'" +
                "        }," +
                "        {" +
                "          sub_title: '위치정보 이용/제공'," +
                "          target: '이 약관에 명시되지 않은 사항은 위치정보의 보호 및 이용 등에 관한 법률, 정보통신망 이용촉진 및 정보보호 등에 관한 법률, 전기통신기본법, 전기통신사업법 등 관계법령과 회사의 이용약관 및 개인정보취급방침, 회사가 별도로 정한 지침 등에 의합니다.'" +
                "        }," +
                "        {" +
                "          sub_title: '수집목적'," +
                "          target: '현재의 위치를 기반으로 하여 주변 매장의 위치 등의 정보를 제공하는 서비스'" +
                "        }," +
                "        {" +
                "          sub_title: '위치정보 보유기간'," +
                "          target: '1년'" +
                "        }" +
                "      ]" +
                "    }," +
                "    {" +
                "      sequence: 3," +
                "      title: '제3자 정보제공 동의서'," +
                "      is_mandatory: 'true'," +
                "      terms_id: '3rdparty'," +
                "      terms_ver: '1.0'," +
                "      agreement: 'initial서비스(이하“서비스”라 한다)와 관련하여, 본인은 동의 내용을 숙지하였으며, 이에 따라 본인의 개인정보를 귀사(이슈어)가 수집한 개인정보를 아래와 같이 제3자에게 제공하는 것에 대해 동의 합니다. 고객은 개인정보의 제3자 제공에 대한 동의를 거부할 권리가 있으며, 동의를 거부할 시 받는 별도의 불이익은 없습니다. 단, 서비스 이용이 불가능하거나, 서비스 이용 목적에 따른 서비스 제공에 제한이 따르게 됩니다.'," +
                "      condition: [" +
                "        {" +
                "          sub_title: '제공하는 자'," +
                "          target: '발급기관'" +
                "        }," +
                "        {" +
                "          sub_title: '제공받는 자'," +
                "          target: '이니셜(SK텔레콤)'" +
                "        }," +
                "        {" +
                "          sub_title: '제공받는 항목'," +
                "          target: '생년월일,시험일,성명(영문),만료일,성명(한글),수험번호,듣기점수,읽기점수,총점'" +
                "        }," +
                "        {" +
                "          sub_title: '수집 및 이용목적'," +
                "          target: '모바일 전자증명서 발급'" +
                "        }," +
                "        {" +
                "          sub_title: '보유 및 이용기간'," +
                "          target: '모바일 전자증명서 발급을 위해 서버에 임시 저장하였다가, 증명서 발행 후 즉시 삭제(단, 고객 단말기 내부 저장영역에 증명서 형태로 저장/보관)'" +
                "        }" +
                "      ]" +
                "    }" +
                "  ]"+
                "}").jsonString();

        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId + "'," +
                "  verification_template_id: '" + toeicVerifTplId + "'," +
                "  agreement: " + agreement +
                "}").jsonString();
        String response = client.requestPOST(agentApiUrl + "/present-proof/send-verification-request", accessToken, body);
        log.info("response: " + response);
    }


    public boolean isAgreementAgreed(String content) {
        try {
            String decisionContent = JsonPath.parse((LinkedHashMap)JsonPath.read(content, "$.content")).jsonString();
            log.info("decisionContent: " + decisionContent);
            String agree = JsonPath.read(decisionContent, "$.agree_yn");
            if (agree.equals("Y"))
                return true;
            log.warn("Agreement is not Agreed  -> Ignore");
        } catch (PathNotFoundException e) {
            log.warn("Invalid content format  -> Ignore");
        }

        return false;
    }

    public LinkedHashMap<String, String> getPresentationResult(String presExRecord) {
        String presExId = JsonPath.read(presExRecord, "$.presentation_exchange_id");
        String verified = JsonPath.read(presExRecord, "$.verified");
        if (!verified.equals("true")) {
            log.info("proof is not verified");
            log.info("Possible Reason: Revoked or Signature mismatch or Predicates unsatisfied");
            return null;
        }
        String requestedProof = JsonPath.parse((LinkedHashMap)JsonPath.read(presExRecord, "$.presentation.requested_proof")).jsonString();

        LinkedHashMap<String, Object> revealedAttrs = JsonPath.read(requestedProof, "$.revealed_attrs");
        for(String key : revealedAttrs.keySet())
            attrs.put(key, JsonPath.read(revealedAttrs.get(key), "$.raw"));
        for(String key : attrs.keySet())
            log.info("Requested Attribute - " + key + ": " + attrs.get(key));

        LinkedHashMap<String, Object> predicates = JsonPath.read(requestedProof, "$.predicates");
        for(String key : predicates.keySet())
            log.info("Requested Predicates - " + key + " is satisfied");

        // TODO self-attested attribute를 출력
        LinkedHashMap<String, Object> selfAttestedAttrs = JsonPath.read(requestedProof, "$.self_attested_attrs");
        for(String key : selfAttestedAttrs.keySet())
            selfAttrs.put(key, JsonPath.read(selfAttestedAttrs.get(key), "$"));
        for (String key : selfAttrs.keySet()) {
            log.info("Self-Attested Attribute - " + key + ": " + selfAttrs.get(key));
            checkSchoolId(presExId, selfAttrs.get(key));
        }
        return attrs;
    }

    public String getPresentationName(String presExRecord) {
        String requestedProofName = JsonPath.read(presExRecord, "$.presentation_request.name");
            log.info("Requested Proof Name - " + requestedProofName);
        return requestedProofName;
    }

    public void sendCredentialOffer(String connectionId, LinkedHashMap<String, String> attrs, String selectedItemId, String eng_name) {
        // TODO: need to implement business logic to query information for holder
        // we assume that the value is obtained by querying DB (e.g., attrs.mobileNum and selectedItemId)
        LinkedHashMap<String, String> value = new LinkedHashMap<>();
        value.put("date_of_birth", attrs.get("모바일 가입증명 (1.0) date_of_birth"));
        value.put("date_of_test", selectedItemId);
        value.put("english_name", eng_name);
        value.put("exp_date", getRandomInt(2024, 2026) + "0228");
        value.put("korean_name", attrs.get("모바일 가입증명 (1.0) person_name"));
        value.put("registration_number", "123456789-987654321");
        value.put("score_of_listening", getRandomInt(100, 444) + "");
        value.put("score_of_reading", "");
        value.put("score_of_total", "990");

        // value insertion
        String body = JsonPath.parse("{" +
                "  \"counter_proposal\": {" +
                "    \"cred_def_id\": " + credDefId + "," +
                "    \"auto_remove\": true," +
                "    \"comment\": \"JJ Test\"," +
                "    \"credential_proposal\": {" +
                "      \"attributes\": [" +
                "        { \"name\": \"date_of_birth\", \"value\": \"" + value.get("date_of_birth")  + "\" }," +
                "        { \"name\": \"date_of_test\", \"value\": \"" + value.get("date_of_test") + "\" }," +
                "        { \"name\": \"english_name\", \"value\": \"" + value.get("english_name") + "\" }," +
                "        { \"name\": \"exp_date\", \"value\": \"" +  value.get("exp_date")  + "\" }," +
                "        { \"name\": \"korean_name\", \"value\": \"" + value.get("korean_name") + "\" }" +
                "        { \"name\": \"registration_number\", \"value\": \"" + value.get("registration_number") + "\" }" +
                "        { \"name\": \"score_of_listening\", \"value\": \"" + value.get("score_of_listening") + "\" }" +
                "        { \"name\": \"score_of_reading\", \"value\": \"" + value.get("score_of_reading") + "\" }" +
                "        { \"name\": \"score_of_total\", \"value\": \"" + value.get("score_of_total") + "\" }" +
                "      ]" +
                "    }" +
                "  }" +
                "}").jsonString();
        String credExId = connIdToCredExId.get(connectionId);
        String response = client.requestPOST(agentApiUrl + "/issue-credential/records/" + credExId + "/send-offer", accessToken, body);
        log.info("response: " + response);
    }

    public void sendCredentialOfferConnectionId(String connectionId, LinkedHashMap<String, String> attrs, String selectedItemId, String eng_name) {
        // TODO: need to implement business logic to query information for holder
        // we assume that the value is obtained by querying DB (e.g., attrs.mobileNum and selectedItemId)
        LinkedHashMap<String, String> value = new LinkedHashMap<>();
        value.put("date_of_birth", attrs.get("모바일 가입증명 (1.0) date_of_birth"));
        value.put("date_of_test", selectedItemId);
        value.put("english_name", eng_name);
        value.put("exp_date", getRandomInt(2023, 2025) + "0228");
        value.put("korean_name", attrs.get("모바일 가입증명 (1.0) person_name"));
        value.put("registration_number", "123456789-987654321");
        value.put("score_of_listening", getRandomInt(100, 444) + "");
        value.put("score_of_reading", "");
        value.put("score_of_total", "990");

        // value insertion
        String body = JsonPath.parse("{" +
                "    \"cred_def_id\": " + credDefId + "," +
                "    \"connection_id\": " + connectionId + "," +
                "    \"auto_remove\": true," +
                "    \"comment\": \"JJ Test\"," +
                "    \"credential_preview\": {" +
                "      \"attributes\": [" +
                "        { \"name\": \"date_of_birth\", \"value\": \"" + value.get("date_of_birth")  + "\" }," +
                "        { \"name\": \"date_of_test\", \"value\": \"" + value.get("date_of_test") + "\" }," +
                "        { \"name\": \"english_name\", \"value\": \"" + value.get("english_name") + "\" }," +
                "        { \"name\": \"exp_date\", \"value\": \"" +  value.get("exp_date")  + "\" }," +
                "        { \"name\": \"korean_name\", \"value\": \"" + value.get("korean_name") + "\" }" +
                "        { \"name\": \"registration_number\", \"value\": \"" + value.get("registration_number") + "\" }" +
                "        { \"name\": \"score_of_listening\", \"value\": \"" + value.get("score_of_listening") + "\" }" +
                "        { \"name\": \"score_of_reading\", \"value\": \"" + value.get("score_of_reading") + "\" }" +
                "        { \"name\": \"score_of_total\", \"value\": \"" + value.get("score_of_total") + "\" }" +
                "      ]" +
                "    }" +
                "}").jsonString();
        //String credExId = connIdToCredExId.get(connectionId);
        String response = client.requestPOST(agentApiUrl + "/issue-credential/send-offer", accessToken, body);
        log.info("sendCredentialOfferConnectionId response: " + response);
    }

    public void sendWebView(String connectionId, LinkedHashMap<String, String> attrs, String presExRecord) {
        // TODO: need to implement business logic to query information for holder and prepare web view
        // we send web view form page (GET webViewUrl?connectionId={connectionId}) to holder in order to select a item by user
        // This web view page will submit connectionId and selectedItemId to POST /web-view/submit

        String initialWebView = JsonPath.parse("{" +
                "  type : 'initial_web_view',"+
                "  content: {" +
                "    web_view_url : '" + webViewUrl + "?connectionId=" + connectionId + "'," +
                "  }"+
                "}").jsonString();
        String body = JsonPath.parse("{ content: '" + initialWebView  + "' }").jsonString();
        String response = client.requestPOST(agentApiUrl + "/connections/" + connectionId + "/send-message", accessToken, body);
        log.info("response: " + response);
    }

    public void handleWebView(String body) {
        log.info("handleWebView >>> body:" + body);

        String connectionId = JsonPath.read(body, "$.connectionId");
        String selectedItemId = JsonPath.read(body, "$.selectedItemId");
        String eng_name = JsonPath.read(body, "$.eng_name");

        // 3-1-1. 추가 정보 기반으로 증명서 발행
        log.info("###### sendCredentialOffer with connectionId:" + connectionId + ", selectedItemId:" + selectedItemId + ", eng_name:" + eng_name);
        sendCredentialOffer(connectionId, attrs, selectedItemId, eng_name);
        //sendCredentialOfferConnectionId(connectionId, attrs, selectedItemId, eng_name);
    }

    public void revokeCredential(String credExId) {
        log.info("revokeCredential >>> credExId:" + credExId );
        log.info("revokeCredential >>> credExId:" + credExId );
        String body = JsonPath.parse("{" +
                "  cred_ex_id: '" + credExId + "'," +
                "  publish: true" +
                "}").jsonString();
        String response =  client.requestPOST(agentApiUrl + "/revocation/revoke", accessToken, body);
        log.info("response: " + response);
    }

    public boolean checkSchoolId(String credExId, String schoolId) {
        try {
            if (!schoolId.equals("1111")){
                log.warn("This school_id " + schoolId + " is OK");
                return true;
            } else {
                log.warn("This school_id " + schoolId + " is NOT OK");
                log.info("credential_exchange_id is " + credExId);
                sendCredProblemReport(credExId, "학번을 찾을 수가 없습니다.");
                return false;
            }
        } catch (PathNotFoundException e) {
            log.warn("Requested credDefId does not exist -> problemReport");
            sendCredProblemReport(credExId, "학번을 입력하지 않았습니다.");
        }
        return false;
    }
}
