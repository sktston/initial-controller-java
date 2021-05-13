package com.sktelecom.initial.controller.holder;

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
import java.util.Timer;
import java.util.TimerTask;

import static com.sktelecom.initial.controller.utils.Common.*;

@RequiredArgsConstructor
@Service
@Slf4j
public class GlobalService {
    private final HttpClient client = new HttpClient();

    // sample mobile credential definition identifier
    final String sampleMobileCredDefId = "4UvAbFrGzyzH6zhvfDSvxf:3:CL:1618987943:707ca438-2198-465c-8e8d-1b8ab8cef021";
    // sample mobile credential issuer controller url
    final String sampleMobileIssuerControllerUrl = "http://221.168.33.78:8044";

    @Value("${agentApiUrl}")
    private String agentApiUrl; // agent service api url

    @Value("${accessToken}")
    private String accessToken; // controller access token

    @Value("${issuerControllerUrl}")
    private String issuerControllerUrl; // issuer controller url to receive invitation-url

    @Value("${issuerCredDefId}")
    private String issuerCredDefId; // credential definition identifier to receive

    String orgName;
    String orgImageUrl;
    String publicDid;
    String phase;

    int iterations = 100; // for long-term test

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        provisionController();

        log.info("Controller is ready");
        log.info("Controller configurations");
        log.info("------------------------------");
        log.info("- organization name: " + orgName);
        log.info("- organization imageUrl: " + orgImageUrl);
        log.info("- public did: " + publicDid);
        log.info("- controller access token: " + accessToken);
        log.info("- issuer controller url to receive invitation-irl: " + issuerControllerUrl);
        log.info("- credential definition id to receive from issuer: " + issuerCredDefId);
        log.info("------------------------------");

        log.info("Preparation - start");
        phase = "preparation";
        if (existSampleMobileCredential()) {
            log.info("Use existing sample mobile credential");
            log.info("Preparation - done");
            startDemo();
        }
        else {
            log.info("Receive sample mobile credential");
            receiveInvitationUrl(sampleMobileIssuerControllerUrl);
        }
    }

    public void handleEvent(String body) {
        String topic = JsonPath.read(body, "$.topic");
        String state = topic.equals("problem_report") ? null : JsonPath.read(body, "$.state");
        log.info("handleEvent >>> topic:" + topic + ", state:" + state + ", body:" + body);

        switch(topic) {
            case "connections":
                // 1. connection 이 완료됨 -> credential 을 요청함
                if (state.equals("active")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendCredentialProposal");
                    sendCredentialProposal(JsonPath.read(body, "$.connection_id"), issuerCredDefId);
                }
                break;
            case "issue_credential":
                // 4-2. 증명서 preview 받음 -> 증명서 요청
                if (state.equals("offer_received")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendCredentialRequest");
                    sendCredentialRequest(JsonPath.read(body, "$.credential_exchange_id"));
                }
                // 4-3. 증명서를 정상 저장하였음 -> 완료
                else if (state.equals("credential_acked")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> credential received successfully");
                    delayedExit();
                }
                break;
            case "basicmessages":
                String content = JsonPath.read(body, "$.content");
                String type = getTypeFromBasicMessage(content);
                // 2. 개인정보이용 동의 요청 받음 -> 동의하여 전송
                if (type != null && type.equals("initial_agreement")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ", type:" + type + ") -> sendAgreementAgreed");
                    sendAgreementAgreed(JsonPath.read(body, "$.connection_id"), content);
                }
                // 4-1. web view를 통한 추가 정보 요구 -> 선택하여 전송
                else if (type != null && type.equals("initial_web_view")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ", type:" + type + ") -> showWebViewAndSelect");
                    showWebViewAndSelect(content);
                }
                else
                    log.warn("- Warning: Unexpected type:" + type);
                break;
            case "present_proof":
                // 3. 모바일 가입증명 검증 요청 받음 -> 모바일 가입 증명 검증 전송
                if (state.equals("request_received")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendPresentation");
                    String presentationRequest = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.presentation_request")).jsonString();
                    sendPresentation(JsonPath.read(body, "$.presentation_exchange_id"), presentationRequest);
                }
                break;
            case "problem_report":
                log.warn("- Case (topic:" + topic + ") -> Print body");
                log.warn("  - body:" + prettyJson(body));
                break;
            case "revocation_registry":
            case "issuer_cred_rev":
                break;
            default:
                log.warn("- Warning: Unexpected topic:" + topic);
        }
    }

    public void handleEventOnPreparation(String body) {
        String topic = JsonPath.read(body, "$.topic");
        String state = topic.equals("problem_report") ? null : JsonPath.read(body, "$.state");
        log.info("handleEvent >>> topic:" + topic + ", state:" + state + ", body:" + body);

        switch(topic) {
            case "connections":
                if (state.equals("active")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendCredentialProposal");
                    sendCredentialProposal(JsonPath.read(body, "$.connection_id"), sampleMobileCredDefId);
                }
                break;
            case "issue_credential":
                if (state.equals("offer_received")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendCredentialRequest");
                    sendCredentialRequest(JsonPath.read(body, "$.credential_exchange_id"));
                }
                else if (state.equals("credential_acked")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sample mobile credential received successfully");
                    log.info("Preparation - done");
                    startDemo();
                }
                break;
            case "problem_report":
                log.warn("- Case (topic:" + topic + ") -> Print body");
                log.warn("  - body:" + prettyJson(body));
                break;
            case "basicmessages":
            case "present_proof":
            case "revocation_registry":
            case "issuer_cred_rev":
                break;
            default:
                log.warn("- Warning Unexpected topic:" + topic);
        }
    }

    public void provisionController() {
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

    public boolean existSampleMobileCredential() {
        String response = client.requestGET(agentApiUrl + "/credentials", accessToken);
        log.info("response: " + response);

        ArrayList<Object> credentials = JsonPath.read(response, "$.results");
        for (Object element : credentials) {
            String credDefId = JsonPath.read(element, "$.cred_def_id");
            if (credDefId.equals(sampleMobileCredDefId))
                return true;
        }
        return false;
    }

    public void startDemo() {
        phase = "started";
        log.info("Receive invitation from issuer controller");
        receiveInvitationUrl(issuerControllerUrl);
    }

    public void receiveInvitationUrl(String controllerUrl) {
        String invitationUrl = client.requestGET(controllerUrl + "/invitation-url", "");
        if (invitationUrl == null) {
            log.warn("Invalid invitationUrl");
            return;
        }
        log.info("invitation-url: " + invitationUrl);
        String invitation = parseInvitationUrl(invitationUrl);
        log.info("invitation: " + invitation);
        String response = client.requestPOST(agentApiUrl + "/connections/receive-invitation", accessToken, invitation);
        log.info("response: " + response);
    }

    public void sendCredentialProposal(String connectionId, String credDefId) {
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId  + "'," +
                "  cred_def_id: '" + credDefId + "'," +
                "}").jsonString();
        String response = client.requestPOST(agentApiUrl + "/issue-credential/send-proposal", accessToken, body);
        log.info("response: " + response);
    }

    public void sendAgreementAgreed(String connectionId, String content) {
        try {
            String agreementContent = JsonPath.parse((LinkedHashMap)JsonPath.read(content, "$.content")).jsonString();
            log.info("agreementContent: " + agreementContent);
        } catch (PathNotFoundException e) {
            log.warn("Invalid content format  -> Ignore");
            return;
        }

        // assume user accept this agreement
        String initialAgreementDecision = JsonPath.parse("{" +
                "  type: 'initial_agreement_decision'," +
                "  content: {" +
                "    agree_yn :'Y'," +
                "    signature:'message signature',"+
                "  }" +
                "}").jsonString();
        String body = JsonPath.parse("{ content: '" + initialAgreementDecision  + "' }").jsonString();
        String response = client.requestPOST(agentApiUrl + "/connections/" + connectionId + "/send-message", accessToken, body);
        log.info("response: " + response);
    }

    public void sendPresentation(String presExId, String presentationRequest) {
        String response = client.requestGET(agentApiUrl + "/present-proof/records/" + presExId + "/credentials", accessToken);
        log.info("Matching Credentials in my wallet: " + response);

        ArrayList<LinkedHashMap<String, Object>> credentials = JsonPath.read(response, "$");
        int credRevId = 0;
        String credId = null;
        for (LinkedHashMap<String, Object> element : credentials) {
            if (JsonPath.read(element, "$.cred_info.cred_rev_id") != null){ // case of support revocation
                int curCredRevId = Integer.parseInt(JsonPath.read(element, "$.cred_info.cred_rev_id"));
                if (curCredRevId > credRevId) {
                    credRevId = curCredRevId;
                    credId = JsonPath.read(element, "$.cred_info.referent");
                }
            }
            else { // case of not support revocation
                credId = JsonPath.read(element, "$.cred_info.referent");
            }
        }
        log.info("Use latest credential in demo - credId: "+ credId);

        // Make body using presentationRequest
        LinkedHashMap<String, Object> reqAttrs = JsonPath.read(presentationRequest, "$.requested_attributes");
        for(String key : reqAttrs.keySet())
            reqAttrs.replace(key, JsonPath.parse("{ cred_id: '" + credId + "', revealed: true }").json());

        LinkedHashMap<String, Object> reqPreds = JsonPath.read(presentationRequest, "$.requested_predicates");
        for(String key : reqPreds.keySet())
            reqPreds.replace(key, JsonPath.parse("{ cred_id: '" + credId + "' }").json());

        LinkedHashMap<String, Object> selfAttrs = new LinkedHashMap<>();

        String body = JsonPath.parse("{}").put("$", "requested_attributes", reqAttrs)
                .put("$", "requested_predicates", reqPreds)
                .put("$", "self_attested_attributes", selfAttrs).jsonString();

        response = client.requestPOST(agentApiUrl + "/present-proof/records/" + presExId + "/send-presentation", accessToken, body);
        log.info("response: " + response);
    }

    public void showWebViewAndSelect(String content) {
        try {
            String webViewContent = JsonPath.parse((LinkedHashMap)JsonPath.read(content, "$.content")).jsonString();
            log.info("webViewContent: " + webViewContent);
            String webViewUrl = JsonPath.read(webViewContent, "$.web_view_url");

            // we assume that we show web view to user and user select & submit a item -> POST /web-view/{presExId}
            String[] token = webViewUrl.split("web-view/");
            String presExId = token[1];
            String body = JsonPath.parse("{ selected_item_id: 'a-single-selected-item-id' }").jsonString();
            String response = client.requestPOST(issuerControllerUrl + "/web-view/" + presExId, "", body);
            log.info("response: " + response);
        } catch (PathNotFoundException e) {
            log.warn("Invalid content format  -> Ignore");
        }
    }

    public void sendCredentialRequest(String credExId) {
        String response = client.requestPOST(agentApiUrl + "/issue-credential/records/" + credExId + "/send-request", accessToken, "{}");
        log.info("response: " + response);
    }

    public void delayedExit() {
        TimerTask task = new TimerTask() {
            public void run() {
                if (--iterations == 0) {
                    log.info("Holder demo completes - Exit");
                    System.exit(0);
                }
                else {
                    log.info("Remaining iterations : " + iterations);
                    startDemo();
                }
            }
        };
        Timer timer = new Timer("Timer");
        timer.schedule(task, 100L);
    }
}