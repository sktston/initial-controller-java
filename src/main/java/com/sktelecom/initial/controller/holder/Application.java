package com.sktelecom.initial.controller.holder;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.sktelecom.initial.controller.utils.Common;
import com.sktelecom.initial.controller.utils.HttpClient;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;

public class Application {

    static Logger log = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    static final HttpClient client = new HttpClient();

    // CONFIGURATION
    // EDIT THIS
    static final String appMode = "dev"; // dev prod
    static final String runType = "issue"; // issue verify
    static final String logLevel = "INFO"; // INFO DEBUG

    // Case of runType issue (모바일가입증명 제출 후 발급)
    static String tpIssuerInvitationUrl = "http://221.168.33.105:8043/invitation-url"; // test issuer (default: dev degree issuer)
    static String tpCredDefId = "SxK6LzvMTgmCPTBm3LwRvG:3:CL:1618984624:2b100704-a533-46c1-ba09-8bd36330d779"; // test issuer (default: dev degree issuer)

    // Case of runType verify (모바일가입증명 제출 후 완료)
    static String tpVerifierInvitationUrl = "http://221.168.33.105:8041/invitation-url"; // test verifier (default: dev mobile verifier)

    // STATIC
    // DO NOT CHANGE THIS
    static String mwpUrl;
    static String authUrl;
    static String agentApiUrl;
    static String agentDataStoreUrl;

    static String authUsername;
    static String authPassword;
    static String testUserCI;

    static String mobileIssuerInvitationUrlApi;
    static String mobileCredDefId;

    static String mobileCredProposalSite;
    static String mobileCredProposalReqSeq;
    static String mobileCredProposalEncodeData;


    static int pollingCyclePeriod = 1000; // 1 second
    static int pollingRetryMax = 100; // 100 times

    static String accessToken;

    public static void main(String[] args) {
        // Set log level
        switch (logLevel) {
            case "DEBUG":
                log.setLevel(Level.DEBUG);
                break;
            default:
                log.setLevel(Level.INFO);
        }

        // Set configuration
        switch (appMode) {
            case "dev":
                mwpUrl = "https://dev.mobile-wallet.co.kr"; // dev
                authUsername = "4e37046d-4dca-4fd0-b69f-df2ee1b2"; // dev
                authPassword = "6ea32d8d-4a04-4a0c-a664-50a950ee"; // dev
                mobileIssuerInvitationUrlApi = "https://dev-console.myinitial.io/mobile-issuer-v2/invitation-url"; // dev
                mobileCredDefId = "TBz5HEP6gzwqDDMw3Ci7BU:3:CL:1618987943:4224f310-cd2b-4836-843b-07b666c2bf6b"; // dev
                mobileCredProposalEncodeData = "9INN4y9uwW5M6lZ6GvvaLUjfPoq25xDuD2XZVF7GlpIbKLwf7SJJgRoIhnx3hXwYfc99b5HQ0QFSMQb4BoDXjnV3/WgeAXjgeP4WACN0OSyneD8DhB9nRmda1zkQVQ2WseKtjLD4FZRsbtZWVu5m5fJ1+P76bmYd7nFA3p1HX6oUocY0QVbGhsYRr/nCNccr/5+zOc2ghzVpD2P3KREOx6rZD1DDWWR2PMl6GOth3QXqF9aXSBdbXWKi9imG/QfsVDQwNxbqY6i2yRfkjGYM1P+eIKknUoNJueMzDgEib3K7sV7YZUm+KH2K7ePvAs09"; // dev
                break;
            case "prod":
                mwpUrl = "https://www.mobile-wallet.co.kr"; // prod
                authUsername = "092d9926-f73c-46ea-9c1a-11479de8"; // prod
                authPassword = "d74c243a-d282-43d3-bdde-eb749c78"; // prod
                mobileIssuerInvitationUrlApi = "https://console.myinitial.io/mobile-issuer-v2/invitation-url"; // prod
                mobileCredDefId = "SWiirNiJX7PdVS6Ji8a5tB:3:CL:101:6e43d3b8-ca11-48b5-83be-5adb240520ad"; // prod
                mobileCredProposalEncodeData = "X4BWcp2XFp77xtGlaSTHNmWuziTyQTkW8x9XRHd7OJnaFIoDCv6yUPbBBNV7JEVtNUMdY4goKKEO65A5ctIrtJOxP9DdUv02q7MWaV+J4ML9d+hfhrd7K0BDuD/P1hUssCBTS4dVNJII5VRvCmrnoRKtndfitUqRbYPnCAvUBvRJSxW21fjM2Q40D3wLOb4OdvXS/NY+nB/Xj2QPuYl594zdAEODgDrjdjw+LIILl486HHG+LUgg/jHSn/n7XYk7aWCu2ZHalJIrglalPldTrey/fZSH1rpBf4jUf5LskvULFneq/gvNSL2MQTkYoJR4"; // prod
                break;
            default:
                log.error("invalid appMode");
                System.exit(-1);
        }
        // 공통
        authUrl = mwpUrl + "/auth";
        agentApiUrl = mwpUrl + "/agent/api";
        agentDataStoreUrl = mwpUrl + "/agent/ds";
        testUserCI = "43aZWK3vEJKQCpaCc91kagoiz4SL6BQ8ibDmbksjRpfGo5UDAga5WJhD8v71rjxamc2Twr8YiciuXqVg4PaYXDfB";
        mobileCredProposalSite = "mobile-wallet";
        mobileCredProposalReqSeq = "REQ_SEQ_VALUE";

        // Login to get access token
        log.info("Login with testUserCI to get access token");
        accessToken = getAccessToken();
        log.info("access token: " + accessToken);

        // Receive mobile identification credential
        log.info("Receive mobile identification credential");
        String mobileCredId = receiveMobileCredential();
        printCredentialByCredId(mobileCredId);

        // testing issuer or verifier
        if (runType.equals("issue")) {
            runIssueProcess();
        }
        else if (runType.equals("verify")) {
            runVerifyProcess();
        }

        log.info("Delete mobile identification credential for clean up");
        deleteCredential(mobileCredId);

        log.info("Print credential history");
        printCredentialHistory();

        log.info("Demo completed successfully");
    }

    static String getAccessToken() {
        String form = "username=" + testUserCI +
                "&grant_type=password" +
                "&scope=all";
        String response = client.requestPOSTBasicAuth(authUrl + "/oauth2/token", authUsername, authPassword, form);
        log.debug("response: " + response);
        return JsonPath.read(response, "$.access_token");
    }

    static String receiveMobileCredential() {
        log.info("--- Preparation Process Start ---");

        // Establish Connection
        log.info("[mobile credential] Receive invitation to establish connection");
        String connectionId = receiveInvitation(mobileIssuerInvitationUrlApi);
        log.info("[mobile credential] connection id: " + connectionId);
        waitUntilConnectionState(connectionId, "active");
        log.info("[mobile credential] connection established");

        // Receive Credential
        log.info("[mobile credential] Send credential proposal to receive credential offer");
        String credExId = sendMobileCredentialProposal(connectionId);
        log.info("[mobile credential] credential exchange id: " + credExId);
        waitUntilCredentialExchangeState(credExId, "offer_received");

        log.info("[mobile credential] Send credential request to receive credential");
        sendCredentialRequest(credExId);
        waitUntilCredentialExchangeState(credExId, "credential_acked");
        log.info("[mobile credential] credential received");

        log.info("[mobile credential] Delete connection for clean up");
        deleteConnection(connectionId);

        log.info("--- Preparation Process End ---");

        return getCredentialIdByCredExId(credExId);
    }

    static void runIssueProcess() {
        log.info("--- Issue Process Start ---");

        // Establish Connection
        log.info("Receive invitation to establish connection");
        String connectionId = receiveInvitation(tpIssuerInvitationUrl);
        log.info("connection id: " + connectionId);
        waitUntilConnectionState(connectionId, "active");
        log.info("connection established");

        // Send Credential Proposal
        log.info("Send credential proposal");
        String credExId = sendTpCredentialProposal(connectionId);
        log.info("credential exchange id: " + credExId);

        // Send Presentation of mobile identification credential
        log.info("Receive presentation request for mobile identification credential");
        String presExId = receiveIdByTopicAndState(connectionId, "present_proof", "request_received");
        log.info("presentation exchange id: " + presExId);

        log.info("Print agreement");
        printAgreement(presExId);

        log.info("Print self attested attributes");
        printSelfAttestedAttrs(presExId);

        log.info("Send presentation");
        sendPresentation(presExId);
        waitUntilPresentationExchangeState(presExId, "presentation_acked");
        log.info("presentation acked");

        // Receive last Event
        log.info("Receive last event to check the topic is basicmessages or issue_credential");
        String topic = receiveTopicByCondition(connectionId);

        if (topic.equals("basicmessages")) {
            // (Webview) Receive Basic Message
            log.info("Receive basic message to get webview url");
            String msgId = receiveIdByTopicAndState(connectionId, "basicmessages", "received");
            printWebviewUrl(msgId);
            log.info("Click above webviewUrl and Submit");
        }

        // Receive Credential
        waitUntilCredentialExchangeState(credExId, "offer_received");
        log.info("Send credential request to receive credential");
        sendCredentialRequest(credExId);
        waitUntilCredentialExchangeState(credExId, "credential_acked");
        log.info("credential received");
        String tpCredId = getCredentialIdByCredExId(credExId);
        printCredentialByCredId(tpCredId);

        log.info("Delete third party credential for clean up");
        deleteCredential(tpCredId);

        log.info("Delete connection for clean up");
        deleteConnection(connectionId);

        log.info("--- Issue Process End ---");
    }

    static void runVerifyProcess() {
        log.info("--- Verify Process Start ---");

        // Establish Connection
        log.info("Receive invitation to establish connection");
        String connectionId = receiveInvitation(tpVerifierInvitationUrl);
        log.info("connection id: " + connectionId);
        waitUntilConnectionState(connectionId, "active");
        log.info("connection established");

        // Send Presentation Proposal
        log.info("Send presentation proposal");
        sendMobilePresentationProposal(connectionId);

        // Send Presentation of mobile identification credential
        log.info("Receive presentation request for mobile identification credential");
        String presExId = receiveIdByTopicAndState(connectionId, "present_proof", "request_received");
        log.info("presentation exchange id: " + presExId);

        log.info("Print agreement");
        printAgreement(presExId);

        log.info("Print self attested attributes");
        printSelfAttestedAttrs(presExId);

        log.info("Send presentation");
        sendPresentation(presExId);
        waitUntilPresentationExchangeState(presExId, "presentation_acked");
        log.info("presentation acked");

        log.info("Delete connection for clean up");
        deleteConnection(connectionId);

        log.info("--- Verify Process End ---");
    }

    static void printCredentialByCredId(String credId) {
        String response = client.requestGET(agentApiUrl + "/credential/" + credId, accessToken);
        log.info("credential: " + response);
    }

    static String receiveInvitation(String invitationUrlApi) {
        String invitationUrl = client.requestGET(invitationUrlApi, "");
        log.debug("invitation-url: " + invitationUrl);
        String invitation = Common.parseInvitationUrl(invitationUrl);
        log.info("invitation: " + invitation);

        String response = client.requestPOST(agentApiUrl + "/connections/receive-invitation", accessToken, invitation);
        log.debug("response: " + response);
        return JsonPath.read(response, "$.connection_id");
    }

    static String sendMobileCredentialProposal(String connectionId) {
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
        return JsonPath.read(response, "$.credential_exchange_id");
    }

    static String sendTpCredentialProposal(String connectionId) {
        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId  + "'," +
                "  cred_def_id: '" + tpCredDefId + "'," +
                "}").jsonString();
        String response = client.requestPOST(agentApiUrl + "/issue-credential/send-proposal", accessToken, body);
        log.debug("response: " + response);
        return JsonPath.read(response, "$.credential_exchange_id");
    }

    static void sendMobilePresentationProposal(String connectionId) {
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

    static void sendCredentialRequest(String credExId) {
        String response = client.requestPOST(agentApiUrl + "/issue-credential/records/" + credExId + "/send-request", accessToken, "{}");
        log.debug("response: " + response);
    }

    static String getCredentialIdByCredExId(String credExId) {
        String response = client.requestGET(agentDataStoreUrl + "/issue-credential/records/" + credExId, accessToken);
        log.debug("response: " + response);
        return JsonPath.read(response, "$.credential.referent");
    }

    static void sendPresentation(String presExId) {
        String response = client.requestGET(agentDataStoreUrl + "/present-proof/records/" + presExId, accessToken);
        log.debug("response: " + response);
        String presentationRequest = JsonPath.parse((LinkedHashMap)JsonPath.read(response, "$.presentation_request")).jsonString();

        response = client.requestGET(agentApiUrl + "/present-proof/records/" + presExId + "/credentials", accessToken);
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

    static void printWebviewUrl(String msgId) {
        String response = client.requestGET(agentDataStoreUrl + "/basic-messages/" + msgId, accessToken);
        log.debug("response: " + response);
        String content = JsonPath.read(response, "$.content");
        String contentType = JsonPath.read(content, "$.type");
        if (!contentType.equals("initial_web_view")) {
            log.error("contentType is not initial_web_view");
            System.exit(-1);
        }
        String contentContent = JsonPath.parse((LinkedHashMap)JsonPath.read(content, "$.content")).jsonString();
        String webviewUrl = JsonPath.read(contentContent, "$.web_view_url");
        log.info("WebviewUrl: " + webviewUrl);

        // Update basic message state to done
        String body = JsonPath.parse("{ state: 'done' }").jsonString();
        response = client.requestPOST(agentDataStoreUrl + "/basic-messages/" + msgId + "/update", accessToken, body);
        log.debug("response: " + response);
    }

    static void printAgreement(String presExId) {
        String response = client.requestGET(agentDataStoreUrl + "/present-proof/records/" + presExId, accessToken);
        log.debug("response: " + response);
        String comment = JsonPath.read(response, "$.presentation_request_dict.comment");
        try {
            String agreement = JsonPath.parse((LinkedHashMap) JsonPath.read(comment, "$.agreement")).jsonString();
            log.info("agreement: " + agreement);
        } catch (PathNotFoundException ignored){}
    }

    static void printSelfAttestedAttrs(String presExId) {
        String response = client.requestGET(agentDataStoreUrl + "/present-proof/records/" + presExId, accessToken);
        log.debug("response: " + response);
        String comment = JsonPath.read(response, "$.presentation_request_dict.comment");
        try {
            String selfAttestedAttrs = JsonPath.parse((ArrayList) JsonPath.read(comment, "$.self_attested_attrs")).jsonString();
            log.info("self attested attributes: " + selfAttestedAttrs);
        } catch (PathNotFoundException ignored){}
    }

    static void deleteCredential(String credId) {
        String response = client.requestDELETE(agentApiUrl + "/credential/" + credId, accessToken);
        log.debug("response: " + response);
        log.info("credId:" + credId + " is deleted");
    }

    static void deleteConnection(String connId) {
        String response = client.requestDELETE(agentApiUrl + "/connections/" + connId, accessToken);
        log.debug("response: " + response);
        log.info("connId:" + connId + " is deleted");
    }

    static void waitUntilConnectionState(String connectionId, String state) {
        log.info("Wait until connection (state: " + state + ")");
        for (int retry=0; retry < pollingRetryMax; retry++) {
            String response = client.requestGET(agentDataStoreUrl + "/connections/" + connectionId, accessToken);
            log.debug("response: " + response);
            String resState = JsonPath.read(response, "$.state");
            log.info("connection state:" + resState);
            if (resState.equals(state))
                return;
            Common.sleep(pollingCyclePeriod);
        }
        log.error("timeout - connection is not (state: " + state + ")");
        System.exit(-1);
    }

    static void waitUntilCredentialExchangeState(String credExId, String state) {
        log.info("Wait until credential exchange (state: " + state + ")");
        for (int retry=0; retry < pollingRetryMax; retry++) {
            String response = client.requestGET(agentDataStoreUrl + "/issue-credential/records/" + credExId, accessToken);
            log.debug("response: " + response);
            String resState = JsonPath.read(response, "$.state");
            log.info("credential exchange state: " + resState);
            if (resState.equals("abandoned")) {
                log.info("error message: " + JsonPath.read(response, "$.error_msg"));
                System.exit(-1);
            }
            if (resState.equals(state))
                return;
            Common.sleep(pollingCyclePeriod);
        }
        log.error("timeout - credential exchange is not (state: " + state + ")");
        System.exit(-1);
    }

    static void waitUntilPresentationExchangeState(String presExId, String state) {
        log.info("Wait until presentation exchange (state: " + state + ")");
        for (int retry=0; retry < pollingRetryMax; retry++) {
            String response = client.requestGET(agentDataStoreUrl + "/present-proof/records/" + presExId, accessToken);
            log.debug("response: " + response);
            String resState = JsonPath.read(response, "$.state");
            log.info("presentation exchange state: " + resState);
            if (resState.equals("abandoned")) {
                log.info("error message: " + JsonPath.read(response, "$.error_msg"));
                System.exit(-1);
            }
            if (resState.equals(state))
                return;
            Common.sleep(pollingCyclePeriod);
        }
        log.error("timeout - presentation exchange is not (state: " + state + ")");
        System.exit(-1);
    }

    static String receiveIdByTopicAndState(String connectionId, String topic, String state) {
        log.info("Wait until last event (" + topic + ", " + state + ")");
        for (int retry=0; retry < pollingRetryMax; retry++) {
            String params = "?connection_id=" + connectionId;
            params += "&topic=" + topic;
            String response = client.requestGET(agentDataStoreUrl + "/events/last" + params, accessToken);
            log.debug("response: " + response);
            // no event yet
            if (response == null) {
                Common.sleep(pollingCyclePeriod);
                continue;
            }
            String resTopic = JsonPath.read(response, "$.topic");
            String resState = JsonPath.read(response, "$.state");
            log.info("last event: (" + resTopic + ", " + resState + ")");
            if (resState.equals("abandoned")) {
                log.info("error message: " + JsonPath.read(response, "$.error_msg"));
                System.exit(-1);
            }
            if (resTopic.equals(topic) && resState.equals(state)) {
                switch (topic) {
                    case "issue_credential":
                        return JsonPath.read(response, "$.credential_exchange_id");
                    case "present_proof":
                        return JsonPath.read(response, "$.presentation_exchange_id");
                    case "basicmessages":
                        return JsonPath.read(response, "$.message_id");
                    default:
                        return null;
                }
            }
            Common.sleep(pollingCyclePeriod);
        }
        log.error("timeout - last event is not (" + topic + ", " + state + ")");
        System.exit(-1);
        return null;
    }

    static String receiveTopicByCondition(String connectionId) {
        log.info("Wait until last event (basicmessages, received) or (issue_credential, offer_received)");
        for (int retry=0; retry < pollingRetryMax; retry++) {
            String params = "?connection_id=" + connectionId;
            params += "&topic=issue_credential,basicmessages";
            String response = client.requestGET(agentDataStoreUrl + "/events/last" + params, accessToken);
            log.debug("response: " + response);
            // no event yet
            if (response == null) {
                Common.sleep(pollingCyclePeriod);
                continue;
            }
            String resTopic = JsonPath.read(response, "$.topic");
            String resState = JsonPath.read(response, "$.state");
            log.info("last event: (" + resTopic + ", " + resState + ")");
            if (resState.equals("abandoned")) {
                log.info("error message: " + JsonPath.read(response, "$.error_msg"));
                System.exit(-1);
            }
            if ((resTopic.equals("basicmessages") && resState.equals("received"))
                    || (resTopic.equals("issue_credential") && resState.equals("offer_received"))) {
                return resTopic;
            }
            Common.sleep(pollingCyclePeriod);
        }
        log.error("timeout - last event is not (basicmessages, received) or (issue_credential, offer_received)");
        System.exit(-1);
        return null;
    }

    static void printCredentialHistory() {
        String params = "?page=1&page_size=10";
        String response = client.requestGET(agentDataStoreUrl + "/credential-histories" + params, accessToken);
        log.debug("response: " + response);
        log.info("credential history: " + JsonPath.read(response, "$.results"));
    }

    // Unused functions
    static void deleteCredentialsByCredDefId(String credDefId) {
        String wql = JsonPath.parse("{" +
                "  cred_def_id: '" + credDefId + "'," +
                "}").jsonString();
        String params = "?wql=" + wql;
        String response = client.requestGET(agentApiUrl + "/credentials" + params, accessToken);
        log.debug("response: " + response);
        ArrayList<LinkedHashMap<String, Object>> creds = JsonPath.read(response, "$.results");
        for (LinkedHashMap<String, Object> cred : creds) {
            String credId = JsonPath.read(cred, "$.referent");
            response = client.requestDELETE(agentApiUrl + "/credential/" + credId, accessToken);
            log.debug("response: " + response);
            log.info("deleted credId: " + credId);
        }
    }

    // Unused functions
    static void deleteAllConnections() {
        String response = client.requestGET(agentApiUrl + "/connections", accessToken);
        log.debug("response: " + response);
        ArrayList<LinkedHashMap<String, Object>> conns = JsonPath.read(response, "$.results");
        for (LinkedHashMap<String, Object> conn : conns) {
            String connId = JsonPath.read(conn, "$.connection_id");
            deleteConnection(connId);
        }
    }

    // Unused functions
    static void deleteAllCredentials() {
        String response = client.requestGET(agentApiUrl + "/credentials", accessToken);
        log.debug("response: " + response);
        ArrayList<LinkedHashMap<String, Object>> creds = JsonPath.read(response, "$.results");
        for (LinkedHashMap<String, Object> cred : creds) {
            String credId = JsonPath.read(cred, "$.referent");
            deleteCredential(credId);
        }
    }
}
