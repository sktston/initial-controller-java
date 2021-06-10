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

    @Value("${mobile_credDefId}")
    private String mobile_credDefId; // credential definition identifier

    @Value("${toeic_credDefId}")
    private String toeic_credDefId; // credential definition identifier

    @Value("${psnm_credDefId}")
    private String psnm_credDefId; // credential definition identifier

    @Value("${verifTplId}")
    private String verifTplId; // verification template identifier

    @Value("${webViewUrl}")
    private String webViewUrl; // web view form url

    String orgName;
    String orgImageUrl;
    String publicDid;

    LinkedHashMap<String, String> connIdToCredExId = new LinkedHashMap<>(); // cache to keep credential issuing flow
    LinkedHashMap<String, String> attrs = new LinkedHashMap<>(); // cache to keep credential issuing flow

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
        log.info("- verification template id: " + verifTplId);
        log.info("- controller access token: " + accessToken);
        log.info("------------------------------");
        log.info("Controller is ready");
    }

    public void handleEvent(String body) {
        String topic = JsonPath.read(body, "$.topic");
        String state = topic.equals("problem_report") ? null : JsonPath.read(body, "$.state");
        log.info("handleEvent >>> topic:" + topic + ", state:" + state + ", body:" + body);

        switch(topic) {
            case "issue_credential":
                // 1. holder 가 credential 을 요청함 -> 개인정보이용 동의 요청
                if (state.equals("proposal_received")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> checkCredentialProposal && sendAgreement");
                    if(checkCredentialProposal(body)) {
                        sendAgreement(JsonPath.read(body, "$.connection_id"));
                        sendProofRequest(JsonPath.read(body, "$.connection_id"));
 			//sendAgreement(JsonPath.read(body, "$.connection_id"));
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
                // 2. holder 가 개인정보이용 동의를 보냄 -> 모바일 가입증명 검증 요청
                if (type != null && type.equals("initial_agreement_decision")) {
                    if (isAgreementAgreed(content)) {
                        log.info("- Case (topic:" + topic + ", state:" + state + ", type:" + type + ") -> AgreementAgreed & sendPresentationRequest");
                        //sendPresentationRequest(JsonPath.read(body, "$.connection_id"));
                    }
                }
                else
                    log.warn("- Warning: Unexpected type:" + type);
                break;
            case "present_proof":
                // 3. holder 가 보낸 모바일 가입증명 검증 완료
                if (state.equals("verified")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> getPresentationResult");
                    attrs = getPresentationResult(body);
                    for(String key : attrs.keySet())
                        log.info("Requested Attribute - " + key + ": " + attrs.get(key));

                    if (enableWebView) {
                        // 3-1. 검증 값 정보로 발행할 증명서가 한정되지 않는 경우 추가 정보 요구
                        log.info("Web View enabled -> sendWebView");
                        sendWebView(JsonPath.read(body, "$.connection_id"), attrs, body);
                    }
                    else {
                        // 3-2. 검증 값 정보 만으로 발행할 증명서가 한정되는 경우 증명서 바로 발행
                        log.info("Web View is not used -> sendCredentialOffer");
                        sendCredentialOffer(JsonPath.read(body, "$.connection_id"), attrs, null);
                    }
                }
                break;
            case "problem_report":
                log.warn("- Case (topic:" + topic + ") -> Print body");
                log.warn("  - body:" + prettyJson(body));
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

        if (!verifTplId.equals("")) {
            log.info("TEST - Check if verification template is valid");
            String response = client.requestGET(agentApiUrl + "/verification-templates/" + verifTplId, accessToken);
            log.info("response: " + response);
            LinkedHashMap<String, Object> verifTpl = JsonPath.read(response, "$.verification_template");
            if (verifTpl == null) {
                log.info("- FAILED: " + verifTplId + " does not exists - Check if it is valid");
                System.exit(0);
            }
            log.info("- SUCCESS : " + verifTplId + " exists");
        }
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

    public boolean checkCredentialProposal(String credExRecord) {
        String credentialProposal = JsonPath.parse((LinkedHashMap)JsonPath.read(credExRecord, "$.credential_proposal_dict")).jsonString();
        try {
            String requestedCredDefId = JsonPath.read(credentialProposal, "$.cred_def_id");
            if (requestedCredDefId.equals(credDefId)){
                String connectionId = JsonPath.read(credExRecord, "$.connection_id");
                String credExId = JsonPath.read(credExRecord, "$.credential_exchange_id");
                connIdToCredExId.put(connectionId, credExId);
                return true;
            }
            log.warn("This issuer can issue credDefId:" + credDefId);
            log.warn("But, requested credDefId is " + requestedCredDefId + " -> Ignore");
        } catch (PathNotFoundException e) {
            log.warn("Requested credDefId does not exist -> Ignore");
        }
        return true;
    }

    public void sendAgreement(String connectionId) {

        String initialAgreement = JsonPath.parse("{\n" +
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

        log.info("initialAgreement: " + initialAgreement);
        String body = JsonPath.parse("{ content: '" + initialAgreement  + "' }").jsonString();
        log.info("initialAgreement body: " + body);
        String response = client.requestPOST(agentApiUrl + "/connections/" + connectionId + "/send-message", accessToken, body);
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


/*
    public void sendProofRequest(String connectionId) {
        long curUnixTime = System.currentTimeMillis() / 1000L;
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId + "'," +
                "  proof_request: {" +
                "    name: '모바일가입증명 검증'," +
                "    version: '1.0'," +
                "    requested_attributes: {" +
                "      person_name: {" +
                "        name: 'person_name'," +
                "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "        restrictions: [ {cred_def_id: '" + mobile_credDefId + "'} ]" +
                "      }," +
                "      mobile_num: {" +
                "        name: 'mobile_num'," +
                "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "        restrictions: [ {cred_def_id: '" + mobile_credDefId + "'} ]" +
                "      }," +
                "      score_of_reading: {" +
                "        name: 'score_of_reading'," +
                "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "        restrictions: [ {cred_def_id: '" + toeic_credDefId + "'} ]" +
                "      }" +
               // "      employee_no: {" +
               // "        name: 'score_of_listening'," +
               // "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
               // "        restrictions: [ {cred_def_id: '" + psnm_credDefId + "'} ]" +
               // "      }" +
                "    }," +
                "    requested_predicates: {" +
                "    }" +
                "  }" +
                "}").jsonString();
        log.info("body: " + body);
        String response = client.requestPOST(agentApiUrl + "/present-proof/send-request", accessToken, body);
        log.info("response: " + response);
    }


*/



    public void sendProofRequest(String connectionId) {
        long curUnixTime = System.currentTimeMillis() / 1000L;
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId + "'," +
                "  proof_request: {" +
                "    name: '모바일가입증명 검증'," +
                "    version: '1.0'," +
                "    requested_attributes: {" +
                "      person_name: {" +
                "        name: 'person_name'," +
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
        //log.info("body: " + body);
        String response = client.requestPOST(agentApiUrl + "/present-proof/send-request", accessToken, body);
        log.info("response: " + response);
    }


    public void sendPresentationRequest(String connectionId) {
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId + "'," +
                "  verification_template_id: '" + verifTplId + "'" +
                "}").jsonString();
        String response = client.requestPOST(agentApiUrl + "/present-proof/send-verification-request", accessToken, body);
        log.info("response: " + response);
    }

    public LinkedHashMap<String, String> getPresentationResult(String presExRecord) {
        String verified = JsonPath.read(presExRecord, "$.verified");
        if (!verified.equals("true")) {
            log.info("proof is not verified");
            return null;
        }
        //LinkedHashMap<String, String> attrs = new LinkedHashMap<>();
        LinkedHashMap<String, Object> revealedAttrs = JsonPath.read(presExRecord, "$.presentation.requested_proof.revealed_attrs");
        for(String key : revealedAttrs.keySet())
            attrs.put(key, JsonPath.read(revealedAttrs.get(key), "$.raw"));
        return attrs;
    }

    public void sendCredentialOffer(String connectionId, LinkedHashMap<String, String> attrs, String selectedItemId) {
        // TODO: need to implement business logic to query information for holder
        // we assume that the value is obtained by querying DB (e.g., attrs.mobileNum and selectedItemId)
        LinkedHashMap<String, String> value = new LinkedHashMap<>();
        //value.put("korean_name", "김증명");
        value.put("korean_name", attrs.get("person_name"));
        //log.info(attrs.get("person_name"));
        value.put("english_name", "Kim Initial");
        value.put("registration_number", "123456789");
        value.put("exp_date", "20220228");
        value.put("date_of_birth", "20000228");
        value.put("date_of_test", "20220228");
        value.put("score_of_listening", "445");
        value.put("score_of_reading", "");
        value.put("score_of_total", "990");

        // value insertion
        String body = JsonPath.parse("{" +
                "  counter_proposal: {" +
                "    cred_def_id: '" + credDefId + "'," +
                "    auto_remove: true," +
                "    comment: 'JJ Test'," +
                "    credential_proposal: {" +
                "      attributes: [" +
                "        { name: 'date_of_birth', value: '" + value.get("date_of_birth")  + "' }," +
                "        { name: 'date_of_test', value: " + value.get("date_of_test") + "' }," +
                "        { name: 'english_name', value: '" + value.get("english_name") + "' }," +
                "        { name: 'exp_date', value: '" +  value.get("exp_date")  + "' }," +
                "        { name: 'korean_name', value: '" + value.get("korean_name") + "' }" +
                "        { name: 'registration_number', value: '" + value.get("registration_number") + "' }" +
                "        { name: 'score_of_listening', value: '" + value.get("score_of_listening") + "' }" +
                "        { name: 'score_of_reading', value: '" + value.get("score_of_reading") + "' }" +
                "        { name: 'score_of_total', value: '" + value.get("score_of_total") + "' }" +
                "      ]" +
                "    }" +
                "  }" +
                "}").jsonString();
        String credExId = connIdToCredExId.get(connectionId);
        log.info("body: " + body); 
	String response = client.requestPOST(agentApiUrl + "/issue-credential/records/" + credExId + "/send-offer", accessToken, body);
        log.info("response: " + response);
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

        // 3-1-1. 추가 정보 기반으로 증명서 발행
        log.info("sendCredentialOffer with connectionId:" + connectionId + ", selectedItemId:" + selectedItemId);
        sendCredentialOffer(connectionId, attrs , selectedItemId);
    }

    public void revokeCredential(String credExId) {
        log.info("revokeCredential >>> credExId:" + credExId );
        String body = JsonPath.parse("{" +
                "  cred_ex_id: '" + credExId + "'," +
                "  publish: true" +
                "}").jsonString();
        String response =  client.requestPOST(agentApiUrl + "/revocation/revoke", accessToken, body);
        log.info("response: " + response);
    }
}
