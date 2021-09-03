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
    boolean webhookUrlIsValid = false;

    LinkedHashMap<String, String> connIdToCredExId = new LinkedHashMap<>(); // cache to keep credential issuing flow

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
        if (!webhookUrlIsValid)
            webhookUrlIsValid = true;

        String topic = JsonPath.read(body, "$.topic");
        String state = null;
        try {
            state = JsonPath.read(body, "$.state");
        } catch (PathNotFoundException e) {}
        log.info("handleEvent >>> topic:" + topic + ", state:" + state + ", body:" + body);

        switch(topic) {
            case "connections":
                if (state.equals("active")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendCredentialOffer");
                    String connectionId = JsonPath.read(body, "$.connection_id");
                    sendCredentialOffer(connectionId, null, null);
                }
                break;
            case "issue_credential":
                if (state == null) {
                    log.warn("- Case (topic:" + topic + ", ProblemReport) -> Print Error Message");
                    String errorMsg = JsonPath.read(body, "$.error_msg");
                    log.warn("  - error_msg: " + errorMsg);
                }
                else if (state.equals("credential_acked")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendProofRequest");
                    String connectionId = JsonPath.read(body, "$.connection_id");
                    sendProofRequest(connectionId);
                }
                break;
            case "present_proof":
                if (state == null) {
                    log.warn("- Case (topic:" + topic + ", ProblemReport) -> Print Error Message");
                    String errorMsg = JsonPath.read(body, "$.error_msg");
                    log.warn("  - error_msg: " + errorMsg);
                }
                else if (state.equals("verified")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> getPresentationResult");
                    getPresentationResult(body);
                }
                break;
            case "problem_report":
                log.warn("- Case (topic:" + topic + ") -> Print body");
                log.warn("  - body:" + body);
                break;
            case "basicmessages":
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
                "  counter_proposal: {" +
                "    cred_def_id: '" + credDefId + "'," +
                "    credential_proposal: {" +
                "      attributes: [" +
                "        { name: 'name', value: '" + value.get("name")  + "' }," +
                "        { name: 'date', value: '" + value.get("date") + "' }," +
                "        { name: 'degree', value: '" + value.get("degree") + "' }," +
                "        { name: 'age', value: '" +  value.get("age")  + "' }," +
                "        { name: 'photo', value: '" + value.get("photo") + "' }" +
                "      ]" +
                "    }" +
                "  }" +
                "}").jsonString();
        String credExId = connIdToCredExId.get(connectionId);
        String response = client.requestPOST(agentApiUrl + "/issue-credential/records/" + credExId + "/send-offer", accessToken, body);
        log.info("response: " + response);
    }


    public void sendProofRequest(String connectionId) {
        long curUnixTime = System.currentTimeMillis() / 1000L;
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId + "'," +
                "  proof_request: {" +
                "    name: 'proof_name'," +
                "    version: '1.0'," +
                "    requested_attributes: {" +
                "      name: {" +
                "        name: 'name'," +
                "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }," +
                "      date: {" +
                "        name: 'date'," +
                "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }," +
                "      degree: {" +
                "        name: 'degree'," +
                "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }," +
                "      photo: {" +
                "        name: 'photo'," +
                "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }" +
                "    }," +
                "    requested_predicates: {" +
                "      age: {" +
                "        name: 'age'," +
                "        p_type: '>='," +
                "        p_value: 20," +
                "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }" +
                "    }" +
                "  }" +
                "}").jsonString();
        String response = client.requestPOST(agentApiUrl + "/present-proof/send-request", accessToken, body);
        log.info("response: " + response);
    }

    public LinkedHashMap<String, String> getPresentationResult(String presExRecord) {
        String verified = JsonPath.read(presExRecord, "$.verified");
        if (!verified.equals("true")) {
            log.info("proof is not verified");
            log.info("Possible Reason: Revoked or Signature mismatch or Predicates unsatisfied");
            return null;
        }
        String requestedProof = JsonPath.parse((LinkedHashMap)JsonPath.read(presExRecord, "$.presentation.requested_proof")).jsonString();

        LinkedHashMap<String, Object> revealedAttrs = JsonPath.read(requestedProof, "$.revealed_attrs");
        LinkedHashMap<String, String> attrs = new LinkedHashMap<>();
        for(String key : revealedAttrs.keySet())
            attrs.put(key, JsonPath.read(revealedAttrs.get(key), "$.raw"));
        for(String key : attrs.keySet())
            log.info("Requested Attribute - " + key + ": " + attrs.get(key));

        LinkedHashMap<String, Object> predicates = JsonPath.read(requestedProof, "$.predicates");
        for(String key : predicates.keySet())
            log.info("Requested Predicates - " + key + " is satisfied");

        return attrs;
    }

}