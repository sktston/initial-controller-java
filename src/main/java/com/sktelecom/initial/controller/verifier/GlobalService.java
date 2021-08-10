package com.sktelecom.initial.controller.verifier;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.sktelecom.initial.controller.utils.HttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;

import static com.sktelecom.initial.controller.utils.Common.getTypeFromBasicMessage;
import static com.sktelecom.initial.controller.utils.Common.parseInvitationUrl;

@RequiredArgsConstructor
@Service
@Slf4j
public class GlobalService {
    private final HttpClient client = new HttpClient();

    @Value("${agentApiUrl}")
    private String agentApiUrl; // agent service api url

    @Value("${accessToken}")
    private String accessToken; // controller access token

    @Value("${verifTplId}")
    private String verifTplId; // verification template identifier

    @Value("${webViewUrl}")
    private String webViewUrl; // web view form url

    String orgName;
    String orgImageUrl;
    String publicDid;
    boolean webhookUrlIsValid = false;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterStartup() {
        provisionController();

        log.info("Controller configurations");
        log.info("------------------------------");
        log.info("- organization name: " + orgName);
        log.info("- organization imageUrl: " + orgImageUrl);
        log.info("- public did: " + publicDid);
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
            case "present_proof":
                if (state == null) {
                    log.warn("- Case (topic:" + topic + ", ProblemReport) -> Print Error Message");
                    String errorMsg = JsonPath.read(body, "$.error_msg");
                    log.warn("  - error_msg: " + errorMsg);
                }
                else if (state.equals("proposal_received")) {
                    String connectionId = JsonPath.read(body, "$.connection_id");
                    sendPresentationRequest(connectionId);
                }
                // 3. holder 가 보낸 모바일 가입증명 검증 완료
                else if (state.equals("verified")) {
                    log.info("- Case (topic:" + topic + ", state:" + state + ") -> getPresentationResult");
                    LinkedHashMap<String, String> attrs = getPresentationResult(body);
                    // TODO: store user information
                }
                break;
            case "basicmessages":
                String content = JsonPath.read(body, "$.content");
                String type = getTypeFromBasicMessage(content);
                // 2. holder 가 개인정보이용 동의를 보냄 -> 동의 내용 저장
                if (type != null && type.equals("initial_agreement_decision")) {
                    if (isAgreementAgreed(content)) {
                        log.info("- Case (topic:" + topic + ", state:" + state + ", type:" + type + ") -> AgreementAgreed");
                        // TODO: store agreement decision
                    }
                }
                else
                    log.warn("- Warning: Unexpected type:" + type);
                break;
            case "problem_report":
                log.warn("- Case (topic:" + topic + ") -> Print body");
                log.warn("  - body:" + body);
                break;
            case "connections":
            case "issue_credential":
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

    public void sendPresentationRequest(String connectionId) {
        // sample agreement
        String agreement = JsonPath.parse("{" +
                "  type: 'initial_agreement',"+
                "  content: [" +
                "    {" +
                "      sequence: 1," +
                "      title: '개인정보 수집 및 이용 동의서'," +
                "      is_mandatory: 'true'," +
                "      terms_id: 'person'," +
                "      terms_ver: '1.0'," +
                "      agreement: 'initial서비스(이하“서비스”라 한다)와 관련하여, 본인은 동의 내용을 숙지하였으며, 이에 따라 본인의 개인정보를 귀사(SK텔레콤주식회사)가 수집 및 이용하는 것에 대해 동의 합니다. 본 동의는 서비스의 본질적 기능 제공을 위한 개인정보 수집/이용에 대한 동의로서, 동의를 하는 경우에만 서비스 이용이 가능합니다. 법령에 따른 개인정보의 수집/이용, 계약의 이행/편익 제공을 위한 개인정보 취급 위탁 및 개인정보 취급과 관련된 일반 사항은 서비스의 개인정보 처리 방침에 따릅니다.'," +
                "      condition: [" +
                "        {" +
                "          sub_title: '수집 항목'," +
                "          target: '이름,생년월일'" +
                "        }," +
                "        {" +
                "          sub_title: '수집 및 이용목적'," +
                "          target: '서비스 이용에 따른 본인확인'" +
                "        }," +
                "        {" +
                "          sub_title: '이용기간 및 보유/파기'," +
                "          target: '1년'" +
                "        }" +
                "      ]" +
                "    }," +
                "    {" +
                "      sequence: 2," +
                "      title: '위치정보 수집 및 이용 동의서'," +
                "      is_mandatory: 'true'," +
                "      terms_id: 'location'," +
                "      terms_ver: '1.0'," +
                "      agreement: '이 약관은 이니셜(SK텔레콤)(이하“회사”)가 제공하는 위치 정보사업 또는 위치기반 서비스 사업과 관련하여 회사와 개인 위치 정보주체와의 권리, 의무 및 책임사항, 기타 필요한 사항을 규정함을 목적으로 합니다.'," +
                "      condition: [" +
                "        {" +
                "          sub_title: '위치정보 수집 방법'," +
                "          target: 'GPS칩'" +
                "        }," +
                "        {" +
                "          sub_title: '위치정보 이용/제공'," +
                "          target: '이 약관에 명시되지 않은 사항은 위치정보의 보호 및 이용 등에 관한 법률, 정보통신망 이용촉진 및 정보보호 등에 관한 법률, 전기통신기본법, 전기통신사업법 등 관계법령과 회사의 이용약관 및 개인정보취급방침, 회사가 별도로 정한 지침 등에 의합니다.'" +
                "        }," +
                "        {" +
                "          sub_title: '수집목적'," +
                "          target: '현재의 위치를 기반으로 하여 주변 매장의 위치 등의 정보를 제공하는 서비스'" +
                "        }," +
                "        {" +
                "          sub_title: '위치정보 보유기간'," +
                "          target: '1년'" +
                "        }" +
                "      ]" +
                "    }," +
                "    {" +
                "      sequence: 3," +
                "      title: '제3자 정보제공 동의서'," +
                "      is_mandatory: 'true'," +
                "      terms_id: '3rdparty'," +
                "      terms_ver: '1.0'," +
                "      agreement: 'initial서비스(이하“서비스”라 한다)와 관련하여, 본인은 동의 내용을 숙지하였으며, 이에 따라 본인의 개인정보를 귀사(이슈어)가 수집한 개인정보를 아래와 같이 제3자에게 제공하는 것에 대해 동의 합니다. 고객은 개인정보의 제3자 제공에 대한 동의를 거부할 권리가 있으며, 동의를 거부할 시 받는 별도의 불이익은 없습니다. 단, 서비스 이용이 불가능하거나, 서비스 이용 목적에 따른 서비스 제공에 제한이 따르게 됩니다.'," +
                "      condition: [" +
                "        {" +
                "          sub_title: '제공하는 자'," +
                "          target: '발급기관'" +
                "        }," +
                "        {" +
                "          sub_title: '제공받는 자'," +
                "          target: '이니셜(SK텔레콤)'" +
                "        }," +
                "        {" +
                "          sub_title: '제공받는 항목'," +
                "          target: '생년월일,시험일,성명(영문),만료일,성명(한글),수험번호,듣기점수,읽기점수,총점'" +
                "        }," +
                "        {" +
                "          sub_title: '수집 및 이용목적'," +
                "          target: '모바일 전자증명서 발급'" +
                "        }," +
                "        {" +
                "          sub_title: '보유 및 이용기간'," +
                "          target: '모바일 전자증명서 발급을 위해 서버에 임시 저장하였다가, 증명서 발행 후 즉시 삭제(단, 고객 단말기 내부 저장영역에 증명서 형태로 저장/보관)'" +
                "        }" +
                "      ]" +
                "    }" +
                "  ]"+
                "}").jsonString();

        String body = JsonPath.parse("{" +
                "  connection_id: '" + connectionId + "'," +
                "  verification_template_id: '" + verifTplId + "'," +
                "  agreement: " + agreement +
                "}").jsonString();
        String response = client.requestPOST(agentApiUrl + "/present-proof/send-verification-request", accessToken, body);
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