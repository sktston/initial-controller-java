package com.sktelecom.initial.controller.holder_webhook;

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

    @Value("${mobileIssuerInvitationUrl}")
    private String mobileIssuerInvitationUrl;

    @Value("${mobileCredDefId}")
    private String mobileCredDefId;

    @Value("${mobileCredProposalEncodeData}")
    private String mobileCredProposalEncodeData;

    @Value("${mobileCredProposalSite}")
    private String mobileCredProposalSite;

    @Value("${mobileCredProposalReqSeq}")
    private String mobileCredProposalReqSeq;

    @Value("${tpIssuerInvitationUrl}")
    private String tpIssuerInvitationUrl;

    @Value("${tpCredDefId}")
    private String tpCredDefId;

    @Value("${tpVerifierInvitationUrl}")
    private String tpVerifierInvitationUrl;

    @Value("${runType}")
    private String runType;

    private boolean mobileCredentialReceived = false;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterStartup() {
        log.info("--- Preparation Process Start ---");
        log.info("Receive invitation from mobile credential issuer");
        receiveInvitation(mobileIssuerInvitationUrl);
    }

    public void handleEvent(String topic, String body) {
        String state = null;
        try {
            state = JsonPath.read(body, "$.state");
        } catch (PathNotFoundException e) {}
        log.info("handleEvent >>> topic:" + topic + ", state:" + state + ", body:" + body);

        // Phase.1 - receive mobile credential
        if (!mobileCredentialReceived) {
            switch(topic) {
                case "connections":
                    if (state.equals("active")) { // #1
                        log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendCredentialProposal");
                        String connectionId = JsonPath.read(body, "$.connection_id");
                        sendMobileCredentialProposal(connectionId);
                    }
                    break;
                case "issue_credential":
                    if (state.equals("offer_received")) { // #2
                        log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendCredentialRequest");
                        String credExId = JsonPath.read(body, "$.credential_exchange_id");
                        sendCredentialRequest(credExId);
                    }
                    else if (state.equals("credential_acked")) { // #3
                        log.info("- Case (topic:" + topic + ", state:" + state + ") -> mobile credential received");
                        String credential = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.credential")).jsonString();
                        log.info("credential: " + credential);
                        log.info("--- Preparation Process End ---");
                        mobileCredentialReceived = true;
                        if (runType.equals("issuer")) {
                            log.info("--- Issue Process Start ---");
                            log.info("receive invitation from third party issuer");
                            receiveInvitation(tpIssuerInvitationUrl);
                        }
                        else {
                            log.info("--- Verify Process Start ---");
                            log.info("receive invitation from third party verifier");
                            receiveInvitation(tpVerifierInvitationUrl);
                        }
                    }
                    else if (state.equals("abandoned")) {
                        log.info("- Case (topic:" + topic + ", state:" + state + ") -> Print Error Message");
                        String errorMsg = JsonPath.read(body, "$.error_msg");
                        log.warn("  - error_msg: " + errorMsg);
                    }
                    break;
                case "present_proof":
                case "basicmessages":
                    break;
                default:
                    log.warn("- Warning Unexpected topic:" + topic);
            }
        }
        // Phase.2-1 - receive third party credential
        else if (runType.equals("issuer")) {
            switch(topic) {
                case "connections":
                    if (state.equals("active")) { // #1
                        log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendTpCredentialProposal");
                        String connectionId = JsonPath.read(body, "$.connection_id");
                        sendTpCredentialProposal(connectionId);
                    }
                    break;
                case "present_proof":
                    if (state.equals("request_received")) { // #2
                        log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendMobilePresentation");
                        String presExId = JsonPath.read(body, "$.presentation_exchange_id");
                        String presentationRequest = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.presentation_request")).jsonString();
                        sendPresentation(presExId, presentationRequest);
                    }
                    else if (state.equals("abandoned")) {
                        log.info("- Case (topic:" + topic + ", state:" + state + ") -> Print Error Message");
                        String errorMsg = JsonPath.read(body, "$.error_msg");
                        log.warn("  - error_msg: " + errorMsg);
                    }
                    break;
                case "issue_credential":
                    if (state.equals("offer_received")) { // #3
                        log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendTpCredentialRequest");
                        String credExId = JsonPath.read(body, "$.credential_exchange_id");
                        sendCredentialRequest(credExId);
                    }
                    else if (state.equals("credential_acked")) { // #4
                        log.info("- Case (topic:" + topic + ", state:" + state + ") -> third party credential received");
                        String credential = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.credential")).jsonString();
                        log.info("credential: " + credential);
                        log.info("--- Issue Process End ---");
                        log.info("issuer demo completed successfully");
                        System.exit(0);
                    }
                    else if (state.equals("abandoned")) {
                        log.info("- Case (topic:" + topic + ", state:" + state + ") -> Print Error Message");
                        String errorMsg = JsonPath.read(body, "$.error_msg");
                        log.warn("  - error_msg: " + errorMsg);
                    }
                    break;
                case "basicmessages":
                    break;
                default:
                    log.warn("- Warning Unexpected topic:" + topic);
            }
        }
        // Phase.2-2 - verify mobile credential
        else if (runType.equals("verifier")) {
            switch(topic) {
                case "connections":
                    if (state.equals("active")) { // #1
                        log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendMobilePresentationProposal");
                        String connectionId = JsonPath.read(body, "$.connection_id");
                        sendMobilePresentationProposal(connectionId);
                    }
                    break;
                case "present_proof":
                    if (state.equals("request_received")) { // #2
                        log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendMobilePresentation");
                        String presExId = JsonPath.read(body, "$.presentation_exchange_id");
                        String presentationRequest = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.presentation_request")).jsonString();
                        sendPresentation(presExId, presentationRequest);
                    }
                    if (state.equals("presentation_acked")) { // #3
                        log.info("- Case (topic:" + topic + ", state:" + state + ") -> mobile presentation acked");
                        log.info("--- Verify Process End ---");
                        log.info("verifier demo completed successfully");
                        System.exit(0);
                    }
                    else if (state.equals("abandoned")) {
                        log.info("- Case (topic:" + topic + ", state:" + state + ") -> Print Error Message");
                        String errorMsg = JsonPath.read(body, "$.error_msg");
                        log.warn("  - error_msg: " + errorMsg);
                    }
                    break;
                case "issue_credential":
                case "basicmessages":
                    break;
                default:
                    log.warn("- Warning Unexpected topic:" + topic);
            }
        }
    }

    public void receiveInvitation(String invitationUrlApi) {
        String invitationUrl = client.requestGET(invitationUrlApi, "");
        log.info("invitation-url: " + invitationUrl);
        String invitation = parseInvitationUrl(invitationUrl);
        if (invitation == null) {
            log.warn("Invalid invitationUrl");
            return;
        }
        log.info("invitation: " + invitation);
        String response = client.requestPOST(agentApiUrl + "/connections/receive-invitation", accessToken, invitation);
        log.info("response: " + response);
    }

    public void sendMobileCredentialProposal(String connectionId) {
        String comment = JsonPath.parse("{" +
                "  site: '" + mobileCredProposalSite + "'," +
                "  req_seq: '" + mobileCredProposalReqSeq + "'," +
                "  encode_data: '" + mobileCredProposalEncodeData + "'," +
                "}").jsonString();
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId  + "'," +
                "  cred_def_id: '" + mobileCredDefId + "'," +
                "  comment: '" + comment + "'," +
                "}").jsonString();
        String response = client.requestPOST(agentApiUrl + "/issue-credential/send-proposal", accessToken, body);
        log.debug("response: " + response);
    }

    public void sendCredentialRequest(String credExId) {
        String response = client.requestPOST(agentApiUrl + "/issue-credential/records/" + credExId + "/send-request", accessToken, "{}");
        log.debug("response: " + response);
    }

    public void sendTpCredentialProposal(String connectionId) {
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId  + "'," +
                "  cred_def_id: '" + tpCredDefId + "'," +
                "}").jsonString();
        String response = client.requestPOST(agentApiUrl + "/issue-credential/send-proposal", accessToken, body);
        log.debug("response: " + response);
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
        log.info("Use latest credential in demo - credential_id: "+ credId);

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
        log.debug("response: " + response);
    }

    public void sendMobilePresentationProposal(String connectionId) {
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId  + "'," +
                "  presentation_proposal: {" +
                "    attributes: []," +
                "    predicates: []" +
                "  }" +
                "}").jsonString();
        String response = client.requestPOST(agentApiUrl + "/present-proof/send-proposal", accessToken, body);
        log.debug("response: " + response);
    }

}
