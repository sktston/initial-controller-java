package com.sktelecom.initial.controller.faber;

import lombok.extern.slf4j.Slf4j;
import org.hyperledger.aries.api.connection.ConnectionRecord;
import org.hyperledger.aries.api.credential.CredentialExchange;
import org.hyperledger.aries.api.message.BasicMessage;
import org.hyperledger.aries.api.message.PingEvent;
import org.hyperledger.aries.api.proof.PresentationExchangeRecord;
import org.hyperledger.aries.api.revocation.RevocationEvent;
import org.hyperledger.aries.webhook.EventHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.inject.Singleton;
import java.io.IOException;
import java.util.Set;

@Singleton
@Component
@Slf4j
public class GlobalEventHandler extends EventHandler {

    @Autowired
    GlobalService globalService;

    @Override
    public void handleConnection(ConnectionRecord connection) {
        log.info("Connection Event: state:{}, {}", connection.getState(), connection);
    }

    @Override
    public void handleCredential(CredentialExchange credential) {
        log.info("Issue Credential Event: state:{}, {}", credential.getState(), credential);
        if (credential.getState().equals("proposal_received")) {
            log.info("state:{} -> sendCredentialOffer", credential.getState());
            try {
                globalService.sendCredentialOffer(credential.getConnectionId(), credential.getCredentialProposalDict());
            } catch (Exception e) { e.printStackTrace(); }
        }
        else if (credential.getState().equals("credential_acked")) {
            log.info("state:{} -> sendPrivacyPolicyOffer", credential.getState());
            try {
                globalService.sendPrivacyPolicyOffer(credential.getConnectionId());
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    @Override
    public void handleBasicMessage(BasicMessage message) {
        log.info("Basic Message Event: state:{}, {}", message.getState(), message);
        if (message.getContent().contains("PrivacyPolicyAgreed")) {
            log.info("PrivacyPolicyAgreed -> sendProofRequest");
            try {
                globalService.sendProofRequest(message.getConnectionId());
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    @Override
    public void handleProof(PresentationExchangeRecord proof) {
        log.info("Present Proof Event: state:{}, {}", proof.getState(), proof);
        if (proof.getState().equals("verified")) {
            log.info("state:{} -> printProofResult", proof.getState());
            try {
                MyCredentialDefinition myCred = proof.from(MyCredentialDefinition.class);
                log.info("myCred: " + myCred);
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    @Override
    public void handlePing(PingEvent ping) {
        log.info("Ping Event: state:{}, {}", ping.getState(), ping);
    }

    @Override
    public void handleRevocation(RevocationEvent revocation) {
        log.info("Revocation Event: state:{}, {}", revocation.getState(), revocation);
    }
}