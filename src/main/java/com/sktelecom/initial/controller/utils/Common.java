package com.sktelecom.initial.controller.utils;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;

@RequiredArgsConstructor
@Slf4j
public class Common {
    public static String parseInvitationUrl(String invitationUrl) {
        String[] tokens = invitationUrl.split("\\?c_i=");
        if (tokens.length != 2)
            return null;

        String encodedInvitation = tokens[1];
        return new String(Base64.decodeBase64(encodedInvitation));
    }

    public static String getTypeFromBasicMessage(String content) {
        try {
            // return type
            return JsonPath.read(content, "$.type");
        } catch (PathNotFoundException ignored) {}
        return null;
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
