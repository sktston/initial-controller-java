package com.sktelecom.initial.controller.faber;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.sktelecom.initial.controller.utils.Common;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hyperledger.aries.AriesClient;
import org.hyperledger.aries.api.connection.CreateInvitationParams;
import org.hyperledger.aries.api.connection.CreateInvitationRequest;
import org.hyperledger.aries.api.connection.CreateInvitationResponse;
import org.hyperledger.aries.api.creddef.CredentialDefinitionFilter;
import org.hyperledger.aries.api.exception.AriesException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@RequiredArgsConstructor
@Service
@Slf4j
public class GlobalService {
    private AriesClient ac;

    @Value("${agentApiUrl}")
    private String agentApiUrl;

    @Value("${controllerToken}")
    private String controllerToken;

    @Value("${credDefId}")
    private String credDefId;

    @Value("${schemaId}")
    private String schemaId;

    String orgName;
    String orgImageUrl;
    String publicDid;

    // for revocation example
    static boolean enableRevoke = Boolean.parseBoolean(System.getenv().getOrDefault("ENABLE_REVOKE", "false"));

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterStartup() throws IOException {
        ac = AriesClient.builder().url(agentApiUrl).token(controllerToken).build();

        provisionController();

        log.info("Controller configurations");
        log.info("------------------------------");
        log.info("- organization name: " + orgName);
        log.info("- organization imageUrl: " + orgImageUrl);
        log.info("- public did: " + publicDid);
        log.info("- credential definition id: " + credDefId);
        log.info("- schema id: " + schemaId);
        log.info("- controllerAccessToken: " + controllerToken);
        log.info("------------------------------");
        log.info("Controller is ready");
    }

    void provisionController() throws IOException {
        log.info("Controller provision - Started");

        log.info("TEST - Create invitation-url");
        try {
            String invitationUrl = createInvitationUrl();
            log.info("- SUCCESS");
            String invitation = Common.parseInvitationUrl(invitationUrl);
            JsonObject object = new Gson().fromJson(invitation, JsonObject.class);
            publicDid = object.get("did").getAsString();
            orgName = object.get("label").getAsString();
            orgImageUrl = object.get("imageUrl").getAsString();
        } catch (AriesException e) {
            log.info("- FAILED: Check if controllerToken is valid - " + controllerToken);
            System.exit(0);
        }

        if (!credDefId.equals("")) {
            log.info("TEST - Check if credential definition is in ledger");
            if (ac.credentialDefinitionsGetById(credDefId).isPresent())
                log.info("- SUCCESS : " + credDefId + " exists");
            else {
                log.info("- FAILED: " + credDefId + " does not exists");
                System.exit(0);
            }

            log.info("TEST - Check if I own the credential definition");
            CredentialDefinitionFilter filter = CredentialDefinitionFilter.builder().credentialDefinitionId(credDefId).build();
            if (!ac.credentialDefinitionsCreated(filter).get().getCredentialDefinitionIds().isEmpty())
                log.info("- YES : I can issue with " + credDefId);
            else
                log.info("- NO: I can not issue with " + credDefId);
        }

        if (!schemaId.equals("")) {
            log.info("TEST - Check if schema is in ledger");
            if (ac.schemasGetById(schemaId).isPresent())
                log.info("- SUCCESS : " + schemaId + " exists");
            else {
                log.info("- FAILED: " + schemaId + " does not exists");
                System.exit(0);
            }
        }
        log.info("Controller provision - Finished");
    }

    public String createInvitationUrl() throws IOException{
        CreateInvitationRequest request = CreateInvitationRequest.builder().build();
        CreateInvitationParams params = CreateInvitationParams.builder().isPublic(true).build();
        CreateInvitationResponse response = ac.connectionsCreateInvitation(request, params).get();
        log.info("createInvitationUrlL invitationUrl: " + response.getInvitationUrl());
        return response.getInvitationUrl();
    }

    /*
    public void handleMessage(String topic, String body) {
        log.info("handleMessage >>> topic:" + topic + ", body:" + body);

        String state = topic.equals("problem_report") ? null : JsonPath.read(body, "$.state");
        switch(topic) {
            case "connections":
                // When connection with alice is done, send credential offer
                if (state.equals("active")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendPrivacyPolicyOffer");
                    sendPrivacyPolicyOffer(JsonPath.read(body, "$.connection_id"));
                }
                else {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> No action in demo");
                }
                break;
            case "issue_credential":
                // When credential is issued and acked, send proof(presentation) request
                if (state.equals("credential_acked")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> sendProofRequest");
                    if (enableRevoke) {
                        revokeCredential(JsonPath.read(body, "$.revoc_reg_id"), JsonPath.read(body, "$.revocation_id"));
                    }
                    sendProofRequest(JsonPath.read(body, "$.connection_id"));
                }
                else {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> No action in demo");
                }
                break;
            case "present_proof":
                // When proof is verified, print the result
                if (state.equals("verified")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> Print result");
                    printProofResult(body);
                }
                else {
                    log.info("- Case (topic:topic:" + topic + ", state:" + state + ") -> No action in demo");
                }
                break;
            case "basicmessages":
                log.info("- Case (topic:" + topic + ", state:" + state + ") -> Print message");
                String message = JsonPath.read(body, "$.content");
                log.info("  - message: " + message);
                if (message.contains("PrivacyPolicyAgreed")) {
                    log.info("- PrivacyPolicyAgreed is contained -> sendCredentialOffer");
                    sendCredentialOffer(JsonPath.read(body, "$.connection_id"));
                }
                break;
            case "revocation_registry":
            case "issuer_cred_rev":
                log.info("- Case (topic:" + topic + ", state:" + state + ") -> No action in demo");
                break;
            case "problem_report":
                log.warn("- Case (topic:" + topic + ") -> Print body");
                log.warn("  - body:" + prettyJson(body));
                break;
            default:
                log.warn("- Warning Unexpected topic:" + topic);
        }
    }

    public void provisionController() {
    }

    public void createUser() {
        String body = JsonPath.parse("{" +
                "  username: '" + username + "'," +
                "  password: '" + password + "'," +
                "  agreeOnNewsletter: false," +
                "  agreeOnTerms: true" +
                "}").jsonString();

        log.info("Create user: " + username);
        String response = client.requestPOST(authUrl + "/users", null, body);
        log.info("response: " + response);

        body = JsonPath.parse("{" +
                "  username: '" + adminUsername + "'," +
                "  password: '" + adminPassword + "'," +
                "  grant_type: 'password'" +
                "}").jsonString();
        log.info("Get jwt token for super admin: " + adminUsername);
        response = client.requestPOSTBasicAuth(authUrl + "/oauth/token", basicUsername, basicPassword, body);
        log.info("response: " + response);
        superAdminToken = JsonPath.read(response, "$.access_token");

        log.info("Validate email to user: " + username);
        response = client.requestPOST(authUrl + "/admin/users/" + username + "/validateEmail", superAdminToken, body);
        log.info("response: " + response);

        log.info("Grand master role to user: " + username);
        response = client.requestPOST(authUrl + "/admin/users/" + username + "/grantMasterRole", superAdminToken, body);
        log.info("response: " + response);

        body = JsonPath.parse("{" +
                "  username: '" + username + "'," +
                "  password: '" + password + "'," +
                "  grant_type: 'password'" +
                "}").jsonString();
        log.info("Get jwt token for user: " + username);
        response = client.requestPOSTBasicAuth(authUrl + "/oauth/token", basicUsername, basicPassword, body);
        log.info("response: " + response);
        userToken = JsonPath.read(response, "$.access_token");
    }

    public void createOrganization() {
        String body = JsonPath.parse("{" +
                "  name: '" + orgName + "'," +
                "  description: '" + orgName + ".description'," +
                "  websiteUrl: 'https://" + orgName + ".me'," +
                "  androidImageUrl: '" + orgImageUrl + "'," +
                "  iosImageX1Url: '" + orgImageUrl + "'," +
                "  iosImageX2Url: '" + orgImageUrl + "'," +
                "  iosImageX3Url: '" + orgImageUrl + "'," +
                "  isDisplayable: true," +
                "  isEnabled: true," +
                "  isIssuer: true," +
                "  isVerifier: true," +
                "  appType: 'all'," +
                "  webhookUrl: '" + orgWebhookUrl + "'" +
                "}").jsonString();

        log.info("Create organization: " + orgName);
        String response = client.requestPOST(authUrl + "/orgs", userToken, body);
        log.info("response: " + response);

        if (JsonPath.read(response, "$.success")) {
            did = JsonPath.read(response, "$.result.did");
            verkey = JsonPath.read(response, "$.result.didVerkey");
            controllerToken = JsonPath.read(response, "$.result.controllerAccessToken");
        }
        else {
            log.info("createOrganization failed");
            System.exit(-1);
        }
    }

    public void createSchema() {
        String body = JsonPath.parse("{" +
                "  schema_name: 'degree_schema'," +
                "  schema_version: '" + version + "'," +
                "  attributes: ['name', 'date', 'degree', 'age']" +
                "}").jsonString();
        log.info("Create a new schema on the ledger:" + body);
        String response = client.requestPOST(agentApiUrl + "/schemas", controllerToken, body);
        log.info("response: " + response);
        schemaId = JsonPath.read(response, "$.schema_id");
    }
    public void createCredentialDefinition() {
        String body = JsonPath.parse("{" +
                "  schema_id: '" + schemaId + "'," +
                "  tag: 'tag." + version + "'," +
                "  support_revocation: true," +
                "  revocation_registry_size: 10" +
                "}").jsonString();
        log.info("Create a new credential definition on the ledger:" + body);
        String response = client.requestPOST(agentApiUrl + "/credential-definitions", controllerToken, body);
        log.info("response: " + response);
        credDefId = JsonPath.read(response, "$.credential_definition_id");
    }

    public void sendPrivacyPolicyOffer(String connectionId) {
        String body = JsonPath.parse("{" +
                "  content: 'PrivacyPolicyOffer. Content here. If you agree, send me a message. PrivacyPolicyAgreed'," +
                "}").jsonString();
        String response = client.requestPOST(agentApiUrl + "/connections/" + connectionId + "/send-message", controllerToken, body);
    }

    public void sendCredentialOffer(String connectionId) {
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId + "'," +
                "  cred_def_id: '" + credDefId + "'," +
                "  credential_preview: {" +
                "    @type: 'did:sov:BzCbsNYhMrjHiqZDTUASHg;spec/issue-credential/1.0/credential-preview'," +
                "    attributes: [" +
                "      { name: 'name', value: 'alice' }," +
                "      { name: 'date', value: '05-2018' }," +
                "      { name: 'degree', value: 'maths' }," +
                "      { name: 'age', value: '25' }" +
                "    ]" +
                "  }" +
                "}").jsonString();
        String response = client.requestPOST(agentApiUrl + "/issue-credential/send-offer", controllerToken, body);
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
                "      attr_name: {" +
                "        name: 'name'," +
                "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }," +
                "      attr_date: {" +
                "        name: 'date'," +
                "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }," +
                "      attr_degree: {" +
                "        name: 'degree'," +
                "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }" +
                "    }," +
                "    requested_predicates: {" +
                "      pred_age: {" +
                "        name: 'age'," +
                "        p_type: '>='," +
                "        p_value: 20," +
                "        non_revoked: { from: 0, to: " + curUnixTime + " }," +
                "        restrictions: [ {cred_def_id: '" + credDefId + "'} ]" +
                "      }" +
                "    }" +
                "  }" +
                "}").jsonString();
        String response = client.requestPOST(agentApiUrl + "/present-proof/send-request", controllerToken, body);
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

     */

    public byte[] generateQRCode(String text, int width, int height) {

        Assert.hasText(text, "text must not be empty");
        Assert.isTrue(width > 0, "width must be greater than zero");
        Assert.isTrue(height > 0, "height must be greater than zero");

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, width, height);
            MatrixToImageWriter.writeToStream(matrix, MediaType.IMAGE_PNG.getSubtype(), outputStream, new MatrixToImageConfig());
        } catch (IOException | WriterException e) {
            e.printStackTrace();
        }
        return outputStream.toByteArray();
    }
}