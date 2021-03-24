package com.sktelecom.initial.controller.alice;

import com.jayway.jsonpath.JsonPath;
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

    @Value("${platformUrl}")
    private String platformUrl; // platform url

    @Value("${controllerToken}")
    private String controllerToken; // controller access token

    @Value("${faberControllerUrl}")
    private String faberControllerUrl; // faber controller url to receive invitation-url

    String agentApiUrl;
    String orgName;
    String orgImageUrl;
    String publicDid;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterStartup() {
        provisionController();

        log.info("Controller configurations");
        log.info("------------------------------");
        log.info("- organization name: " + orgName);
        log.info("- organization imageUrl: " + orgImageUrl);
        log.info("- public did: " + publicDid);
        log.info("- controller access token: " + controllerToken);
        log.info("------------------------------");
        log.info("Controller is ready");

        log.info("Receive invitation from faber controller");
        receiveInvitationUrl();
    }

    public void handleEvent(String topic, String body) {
        String state = topic.equals("problem_report") ? null : JsonPath.read(body, "$.state");
        log.info("handleEvent >>> topic:" + topic + ", state:" + state + ", body:" + body);

        switch(topic) {
            case "connections":
                if (state.equals("active")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendCredentialProposal");
                    sendCredentialProposal(JsonPath.read(body, "$.connection_id"));
                }
                break;
            case "issue_credential":
                if (state.equals("offer_received")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendCredentialRequest");
                    sendCredentialRequest(JsonPath.read(body, "$.credential_exchange_id"));
                }
                break;
            case "basicmessages":
                String content = JsonPath.read(body, "$.content");
                if (content.contains("PrivacyPolicyOffer")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ", PrivacyPolicyOffer) -> sendPrivacyPolicyAgreed");
                    sendPrivacyPolicyAgreed(JsonPath.read(body, "$.connection_id"));
                }
                break;
            case "present_proof":
                if (state.equals("request_received")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendProof");
                    sendProof(body);
                }
                else if (state.equals("presentation_acked")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> delayedExit");
                    delayedExit();
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
                log.warn("- Warning Unexpected topic:" + topic);
        }
    }

    public void provisionController() {
        agentApiUrl = platformUrl + "/agent/api";

        log.info("TEST - Create invitation-url");
        String invitationUrl = createInvitationUrl();
        if (invitationUrl == null) {
            log.info("- FAILED: Check if accessToken is valid - " + controllerToken);
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
        String response = client.requestPOST(agentApiUrl + "/connections/create-invitation" + params, controllerToken, "{}");
        log.info("response: " + response);
        try {
            return JsonPath.read(response, "$.invitation_url");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public void receiveInvitationUrl() {
        String invitationUrl = client.requestGET(faberControllerUrl + "/invitation-url", "");
        if (invitationUrl == null) {
            log.warn("Invalid invitationUrl");
            return;
        }
        log.info("invitation-url: " + invitationUrl);
        String invitation = parseInvitationUrl(invitationUrl);
        log.info("invitation: " + invitation);
        String response = client.requestPOST(agentApiUrl + "/connections/receive-invitation", controllerToken, invitation);
        log.info("response: " + response);
    }

    public void sendCredentialProposal(String connectionId) {
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId  + "'," +
                // uncomment below if you want to request specific credential definition id to faber
                //"  cred_def_id: 'TCXu9qcEoRYX9jWT6CBFAy:3:CL:1614837027:tag'," +
                "}").jsonString();
        String response = client.requestPOST(agentApiUrl + "/issue-credential/send-proposal", controllerToken, body);
        log.info("response: " + response);
    }

    public void sendCredentialRequest(String credExId) {
        String response = client.requestPOST(agentApiUrl + "/issue-credential/records/" + credExId + "/send-request", controllerToken, "{}");
        log.info("response: " + response);
    }

    public void sendPrivacyPolicyAgreed(String connectionId) {
        String body = JsonPath.parse("{" +
                "  content: 'PrivacyPolicyAgreed'," +
                "}").jsonString();
        String response = client.requestPOST(agentApiUrl + "/connections/" + connectionId + "/send-message", controllerToken, body);
        log.info("response: " + response);
    }

    public void sendProof(String reqBody) {
        String presExId = JsonPath.read(reqBody, "$.presentation_exchange_id");
        String response = client.requestGET(agentApiUrl + "/present-proof/records/" + presExId + "/credentials", controllerToken);
        log.info("response: " + response);

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
        log.info("Use latest credential in demo - credRevId: " + credRevId + ", credId: "+ credId);

        // Make body using presentation_request
        LinkedHashMap<String, Object> reqAttrs = JsonPath.read(reqBody, "$.presentation_request.requested_attributes");
        for(String key : reqAttrs.keySet())
            reqAttrs.replace(key, JsonPath.parse("{ cred_id: '" + credId + "', revealed: true }").json());

        LinkedHashMap<String, Object> reqPreds = JsonPath.read(reqBody, "$.presentation_request.requested_predicates");
        for(String key : reqPreds.keySet())
            reqPreds.replace(key, JsonPath.parse("{ cred_id: '" + credId + "' }").json());

        LinkedHashMap<String, Object> selfAttrs = new LinkedHashMap<>();

        String body = JsonPath.parse("{}").put("$", "requested_attributes", reqAttrs)
                .put("$", "requested_predicates", reqPreds)
                .put("$", "self_attested_attributes", selfAttrs).jsonString();

        response = client.requestPOST(agentApiUrl + "/present-proof/records/" + presExId + "/send-presentation", controllerToken, body);
        log.info("response: " + response);
    }

    public void delayedExit() {
        TimerTask task = new TimerTask() {
            public void run() {
                log.info("Alice demo completes - Exit");
                System.exit(0);
            }
        };
        Timer timer = new Timer("Timer");
        timer.schedule(task, 100L);
    }
}