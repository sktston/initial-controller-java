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

    @Value("${verifTplId}")
    private String verifTplId; // verification template identifier

    @Value("${webViewUrl}")
    private String webViewUrl; // web view form url

    String orgName;
    String orgImageUrl;
    String publicDid;

    LinkedHashMap<String, String> presExRecordCache = new LinkedHashMap<>();

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
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> isValidCredentialProposal && sendAgreement");
                    String credentialProposal = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.credential_proposal_dict")).jsonString();
                    if(isValidCredentialProposal(credentialProposal)) {
                        sendAgreement(JsonPath.read(body, "$.connection_id"));
                    }
                }
                // 4. holder 가 증명서를 정상 저장하였음 -> 완료 (revocation 은 아래 코드 참조)
                else if (state.equals("credential_acked")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> credential issued successfully");
                    // TODO: should store credential_exchange_id to revoke this credential later
                    // storeCredExRecord(body);
                    if (enableRevoke) {
                        revokeCredential(JsonPath.read(body, "$.credential_exchange_id"));
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
                        sendPresentationRequest(JsonPath.read(body, "$.connection_id"));
                    }
                }
                else
                    log.warn("- Warning: Unexpected type:" + type);
                break;
            case "present_proof":
                // 3. holder 가 보낸 모바일 가입증명 검증 완료
                if (state.equals("verified")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> getPresentationResult");
                    LinkedHashMap<String, String> attrs = getPresentationResult(body);
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

    public boolean isValidCredentialProposal(String credentialProposal) {
        try {
            String requestedCredDefId = JsonPath.read(credentialProposal, "$.cred_def_id");
            if (requestedCredDefId.equals(credDefId))
                return true;
            log.warn("This issuer can issue credDefId:" + credDefId);
            log.warn("But, requested credDefId is " + requestedCredDefId + " -> Ignore");
        } catch (PathNotFoundException e) {
            log.warn("Requested credDefId does not exist -> Ignore");
        }
        return false;
    }

    public void sendAgreement(String connectionId) {
        String initialAgreement = JsonPath.parse("{" +
                "  type : 'initial_agreement',"+
                "  content: {" +
                "    title : '개인정보 수집 및 이용 동의서'," +
                "    agreement: 'initial 서비스(이하 서비스라 한다)와 관련하여, 본인은 동의내용을 숙지하였으며, 이에 따라 본인의 개인정보를 (주)XXXX가 수집 및 이용하는 것에 대해 동의합니다.\n\n본 동의는 서비스의 본질적 기능 제공을 위한 개인정보 수집/이용에 대한 동의로서, 동의를 하는 경우에만 서비스 이용이 가능합니다.\n\n법령에 따른 개인정보의 수집/이용, 계약의 이행/편익제공을 위한 개인정보 취급위탁 및 개인정보 취급과 관련된 일반 사항은 서비스의 개인정보 처리방침에 따릅니다.'," +
                "    collectiontype: '이름,생년월일'," +
                "    usagepurpose: '서비스 이용에 따른 본인확인'," +
                "    consentperiod : '1년',"+
                "  }"+
                "}").jsonString();
        String body = JsonPath.parse("{ content: '" + initialAgreement  + "' }").jsonString();
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
        LinkedHashMap<String, String> attrs = new LinkedHashMap<>();
        LinkedHashMap<String, Object> revealedAttrs = JsonPath.read(presExRecord, "$.presentation.requested_proof.revealed_attrs");
        for(String key : revealedAttrs.keySet())
            attrs.put(key, JsonPath.read(revealedAttrs.get(key), "$.raw"));
        return attrs;
    }

    public void sendCredentialOffer(String connectionId, LinkedHashMap<String, String> attrs, String selectedItemId) {
        // TODO: need to implement business logic to query information for holder
        // we assume that the value is obtained by querying DB (e.g., attrs.mobileNum and selectedItemId)
        LinkedHashMap<String, String> value = new LinkedHashMap<>();
        value.put("name", "김증명");
        value.put("date", "20180228");
        value.put("degree", "컴퓨터공학");
        value.put("age", "25");
        value.put("photo", "JpegImageBase64EncodedBinary");

        // value insertion
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId + "'," +
                "  cred_def_id: '" + credDefId + "'," +
                "  credential_proposal: {" +
                "    attributes: [" +
                "      { name: 'name', value: '" + value.get("name")  + "' }," +
                "      { name: 'date', value: " + value.get("date") + "' }," +
                "      { name: 'degree', value: '" + value.get("degree") + "' }," +
                "      { name: 'age', value: '" +  value.get("age")  + "' }," +
                "      { name: 'photo', value: '" + value.get("photo") + "' }" +
                "    ]" +
                "  }" +
                "}").jsonString();
        String response = client.requestPOST(agentApiUrl + "/issue-credential/send", accessToken, body);
        log.info("response: " + response);
    }

    public void sendWebView(String connectionId, LinkedHashMap<String, String> attrs, String presExRecord) {
        String presExId = JsonPath.read(presExRecord, "$.presentation_exchange_id");
        presExRecordCache.put(presExId, presExRecord);

        // TODO: need to implement business logic to query information for holder and prepare web view
        // we send web view form page (GET webViewUrl?presExId={presExId}) to holder in order to select a item by user
        // This web view page will submit presExId and selectedItemId to POST /web-view/submit

        String initialWebView = JsonPath.parse("{" +
                "  type : 'initial_web_view',"+
                "  content: {" +
                "    web_view_url : '" + webViewUrl + "?presExId=" + presExId + "'," +
                "  }"+
                "}").jsonString();
        String body = JsonPath.parse("{ content: '" + initialWebView  + "' }").jsonString();
        String response = client.requestPOST(agentApiUrl + "/connections/" + connectionId + "/send-message", accessToken, body);
        log.info("response: " + response);
    }

    public void handleWebView(String body) {
        log.info("handleWebView >>> body:" + body);

        String presExId = JsonPath.read(body, "$.presExId");
        String presExRecord = presExRecordCache.get(presExId);
        String connectionId = JsonPath.read(presExRecord, "$.connection_id");
        LinkedHashMap<String, String> attrs = getPresentationResult(presExRecord);
        String selectedItemId = JsonPath.read(body, "$.selectedItemId");

        // 3-1-1. 추가 정보 기반으로 증명서 발행
        log.info("Found connectionId:" + connectionId + " from presExId:" + presExId);
        log.info("sendCredentialOffer with selectedItemId: " + selectedItemId);
        sendCredentialOffer(connectionId, attrs, selectedItemId);

        presExRecordCache.remove(presExId);
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