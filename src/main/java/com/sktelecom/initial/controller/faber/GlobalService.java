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
import org.hyperledger.aries.api.credential.CredentialExchange;
import org.hyperledger.aries.api.credential.CredentialProposalRequest;
import org.hyperledger.aries.api.message.SendMessageRequest;
import org.hyperledger.aries.api.proof.PresentProofRequest;
import org.hyperledger.aries.api.proof.PresentProofRequestConfig;
import org.hyperledger.aries.api.proof.PresentationExchangeRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.hyperledger.aries.api.proof.PresentProofRequest.ProofRequest.ProofAttributes.*;
import static org.hyperledger.aries.api.proof.PresentProofRequest.*;

@RequiredArgsConstructor
@Service
@Slf4j
public class GlobalService {
    private AriesClient ac;

    @Value("${agentApiUrl}")
    private String agentApiUrl;

    @Value("${accessToken}")
    private String accessToken;

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
        ac = AriesClient.builder().url(agentApiUrl).token(accessToken).build();

        provisionController();

        log.info("Controller configurations");
        log.info("------------------------------");
        log.info("- organization name: " + orgName);
        log.info("- organization imageUrl: " + orgImageUrl);
        log.info("- public did: " + publicDid);
        log.info("- credential definition id: " + credDefId);
        log.info("- schema id: " + schemaId);
        log.info("- controllerAccessToken: " + accessToken);
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
        } catch (Exception e) {
            log.info("- FAILED: Check if accessToken is valid - " + accessToken);
            System.exit(0);
        }

        if (!credDefId.equals("")) {
            log.info("TEST - Check if credential definition is in ledger");
            if (ac.credentialDefinitionsGetById(credDefId).isPresent())
                log.info("- SUCCESS : " + credDefId + " exists");
            else {
                log.info("- FAILED: " + credDefId + " does not exists - Check if it is valid");
                System.exit(0);
            }

            log.info("TEST - Check if this controller owns the credential definition");
            CredentialDefinitionFilter filter = CredentialDefinitionFilter.builder().credentialDefinitionId(credDefId).build();
            if (!ac.credentialDefinitionsCreated(filter).get().getCredentialDefinitionIds().isEmpty())
                log.info("- YES : This controller can issue with " + credDefId);
            else
                log.info("- NO: This controller can not issue with " + credDefId);
        }

        if (!schemaId.equals("")) {
            log.info("TEST - Check if schema is in ledger");
            if (ac.schemasGetById(schemaId).isPresent())
                log.info("- SUCCESS : " + schemaId + " exists");
            else {
                log.info("- FAILED: " + schemaId + " does not exists - Check if it is valid");
                System.exit(0);
            }
        }
        log.info("Controller provision - Finished");
    }

    public String createInvitationUrl() throws IOException {
        CreateInvitationRequest request = CreateInvitationRequest.builder().build();
        CreateInvitationParams params = CreateInvitationParams.builder().isPublic(true).build();
        CreateInvitationResponse response = ac.connectionsCreateInvitation(request, params).get();
        log.info("createInvitationUrlL invitationUrl: " + response.getInvitationUrl());
        return response.getInvitationUrl();
    }

    public void sendCredentialOffer(String connectionId, JsonObject credentialProposal) throws IOException {
        // uncomment below if you want to get specified credential definition id from alice
        //String wantedCredDefId = credentialProposal.get("cred_def_id").getAsString();

        MyCredentialDefinition credDef = MyCredentialDefinition.builder()
                .name("alice")
                .date("05-2018")
                .degree("maths")
                .age("25")
                .build();
        CredentialExchange response = ac.issueCredentialSend(
                new CredentialProposalRequest(connectionId, credDefId, credDef)
        ).get();
        log.info("response: " + response);
    }

    public void sendPrivacyPolicyOffer(String connectionId) throws IOException {
        SendMessageRequest request = new SendMessageRequest("PrivacyPolicyOffer. Content here. If you agree, send me a message. PrivacyPolicyAgreed");
        ac.connectionsSendMessage(connectionId, request);
    }

    public void sendProofRequest(String connectionId) throws IOException {
        Integer curUnixTime = Math.toIntExact(System.currentTimeMillis() / 1000L);
        ProofRestrictions resriction =
                ProofRestrictions.builder().credentialDefinitionId(credDefId).build();
        ProofNonRevoked nonRevoked =
                ProofNonRevoked.builder().toEpoch(curUnixTime).build();
        PresentProofRequestConfig config = PresentProofRequestConfig.builder()
                .connectionId(connectionId)
                .appendAttribute(List.of("name", "date", "degree"), resriction)
                .build();
        PresentationExchangeRecord response = ac.presentProofSendRequest(
                new PresentProofRequest(connectionId, ProofRequest.build(config), null, null)
        ).get();
        log.info("response: " + response);
    }

    /*

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