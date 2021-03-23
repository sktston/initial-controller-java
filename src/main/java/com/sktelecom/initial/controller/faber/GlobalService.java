package com.sktelecom.initial.controller.faber;

import com.jayway.jsonpath.JsonPath;
import com.sktelecom.initial.controller.utils.HttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
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

    @Value("${platformUrl}")
    private String platformUrl; // platform url

    @Value("${controllerToken}")
    private String controllerToken; // controller access token

    @Value("${credDefId}")
    private String credDefId; // credential definition identifier

    @Value("${schemaId}")
    private String schemaId;  // schema identifier

    @Value("${verifTplId}")
    private String verifTplId; // verification template identifier

    String agentApiUrl;
    String orgName;
    String orgImageUrl;
    String publicDid;
    String photoFileName = "images/ci_t.jpg"; // sample image file

    // for revocation example
    static boolean enableRevoke = Boolean.parseBoolean(System.getenv().getOrDefault("ENABLE_REVOKE", "false"));

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterStartup() {
        provisionController();

        log.info("Controller configurations");
        log.info("------------------------------");
        log.info("- organization name: " + orgName);
        log.info("- organization imageUrl: " + orgImageUrl);
        log.info("- public did: " + publicDid);
        log.info("- credential definition id: " + credDefId);
        log.info("- schema id: " + schemaId);
        log.info("- verification template id: " + verifTplId);
        log.info("- controller access token: " + controllerToken);
        log.info("------------------------------");
        log.info("Controller is ready");
    }

    public void handleEvent(String topic, String body) {
        String state = topic.equals("problem_report") ? null : JsonPath.read(body, "$.state");
        log.info("handleEvent >>> topic:" + topic + ", state:" + state + ", body:" + body);

        switch(topic) {
            case "connections":
                break;
            case "issue_credential":
                if (state.equals("proposal_received")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendCredentialOffer");
                    String credentialProposal = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.credential_proposal_dict")).jsonString();
                    sendCredentialOffer(JsonPath.read(body, "$.connection_id"), credentialProposal);
                }
                else if (state.equals("credential_acked")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendPrivacyPolicyOffer");
                    if (enableRevoke) {
                        revokeCredential(JsonPath.read(body, "$.revoc_reg_id"), JsonPath.read(body, "$.revocation_id"));
                    }
                    sendPrivacyPolicyOffer(JsonPath.read(body, "$.connection_id"));
                }
                break;
            case "basicmessages":
                String content = JsonPath.read(body, "$.content");
                if (content.contains("PrivacyPolicyAgreed")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ", PrivacyPolicyAgreed) -> sendProofRequest");
                    sendProofRequest(JsonPath.read(body, "$.connection_id"));
                }
                break;
            case "present_proof":
                if (state.equals("verified")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> Print result");
                    printProofResult(body);
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

    void provisionController() {
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

        if (!credDefId.equals("")) {
            log.info("TEST - Check if credential definition is in ledger");
            String response = client.requestGET(agentApiUrl + "/credential-definitions/" + credDefId, controllerToken);
            log.info("response: " + response);
            LinkedHashMap<String, Object> credDef = JsonPath.read(response, "$.credential_definition");
            if (credDef == null) {
                log.info("- FAILED: " + credDefId + " does not exists - Check if it is valid");
                System.exit(0);
            }
            log.info("- SUCCESS : " + credDefId + " exists");

            log.info("TEST - Check if this controller owns the credential definition");
            String params = "?cred_def_id=" + credDefId;
            response = client.requestGET(agentApiUrl + "/credential-definitions/created" + params, controllerToken);
            log.info("response: " + response);
            ArrayList<String> credDefIds = JsonPath.read(response, "$.credential_definition_ids");
            if (credDefIds.isEmpty())
                log.info("- NO: This controller can not issue with " + credDefId);
            else
                log.info("- YES : This controller can issue with " + credDefId);
        }

        if (!schemaId.equals("")) {
            log.info("TEST - Check if schema is in ledger");
            String response = client.requestGET(agentApiUrl + "/schemas/" + schemaId, controllerToken);
            log.info("response: " + response);
            LinkedHashMap<String, Object> schema = JsonPath.read(response, "$.schema");
            if (schema == null) {
                log.info("- FAILED: " + schemaId + " does not exists - Check if it is valid");
                System.exit(0);
            }
            log.info("- SUCCESS : " + schemaId + " exists");
        }
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

    public void sendCredentialOffer(String connectionId, String credentialProposal) {
        // uncomment below if you want to get requested credential definition id from alice
        //String requestedCredDefId = JsonPath.read(credentialProposal, "$.cred_def_id");

        String encodedImage = "";
        try {
            encodedImage = encodeFileToBase64Binary(photoFileName);
        } catch (Exception e) { e.printStackTrace(); }
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId + "'," +
                "  cred_def_id: '" + credDefId + "'," +
                "  comment: 'credential_comment'," +
                "  credential_proposal: {" +
                "    @type: 'did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/issue-credential/1.0/credential-preview'," +
                "    attributes: [" +
                "      { name: 'name', value: 'alice' }," +
                "      { name: 'date', value: '05-2018' }," +
                "      { name: 'degree', value: 'maths' }," +
                "      { name: 'age', value: '25' }," +
                "      { name: 'photo', value: '" + encodedImage + "', mime-type: 'image/jpeg' }" +
                "    ]" +
                "  }" +
                "}").jsonString();
        String response = client.requestPOST(agentApiUrl + "/issue-credential/send", controllerToken, body);
        log.info("response: " + response);
    }

    public void sendPrivacyPolicyOffer(String connectionId) {
        String body = JsonPath.parse("{" +
                "  content: 'PrivacyPolicyOffer. Content here. If you agree, send me a message. PrivacyPolicyAgreed'," +
                "}").jsonString();
        String response = client.requestPOST(agentApiUrl + "/connections/" + connectionId + "/send-message", controllerToken, body);
        log.info("response: " + response);
    }

    public void sendProofRequest(String connectionId) {
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId + "'," +
                "  verification_template_id: '" + verifTplId + "'" +
                "}").jsonString();
        String response = client.requestPOST(agentApiUrl + "/present-proof/send-verification-request", controllerToken, body);
        log.info("response: " + response);
    }

    public void printProofResult(String body) {
        String requestedProof = JsonPath.parse((LinkedHashMap)JsonPath.read(body, "$.presentation.requested_proof")).jsonString();
        log.info("  - Proof requested:" + prettyJson(requestedProof));
        String verified = JsonPath.read(body, "$.verified");
        log.info("  - Proof validation:" + verified);
    }

    public void revokeCredential(String revRegId, String credRevId) {
        log.info("revokeCredential >>> revRegId:" + revRegId + ", credRevId:" + credRevId);

        HttpUrl.Builder urlBuilder = HttpUrl.parse(agentApiUrl + "/issue-credential/revoke").newBuilder();
        urlBuilder.addQueryParameter("rev_reg_id", revRegId);
        urlBuilder.addQueryParameter("cred_rev_id", credRevId);
        urlBuilder.addQueryParameter("publish", "true");
        String url = urlBuilder.build().toString();

        String response =  client.requestPOST(url, controllerToken, "{}");
        log.info("response: " + response);
    }
}