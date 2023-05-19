package com.sktelecom.initial.controller.utils;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Slf4j
public class Aes256Util {

    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding"; //AES transformation

    /**
     * 컨텐츠를 AES256 암호화하는 메소드.
     *
     * @param key AES 키
     * @param initialVector AES 초기화벡터
     * @param contents 암호화할 컨텐츠
     * @return contents AES256 암호화한 문자열
     * @throws Exception
     */
    public String encrypt(String key, String initialVector, String contents) throws Exception {

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);

        SecretKeySpec secretKeySpec = new SecretKeySpec(hexToBytes(key), "AES");
        IvParameterSpec ivParameterSpec = new IvParameterSpec(hexToBytes(initialVector));

        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivParameterSpec);

        byte[] encrypted = cipher.doFinal(contents.getBytes("UTF-8"));

        return Base64.getEncoder().encodeToString(encrypted);

    }

    /**
     * 암호화된 문자열을 원본 문자열로 복호화하는 메소드.
     *
     * @param key AES 키
     * @param initialVector AES 초기화벡터
     * @param contents 복호화할 컨텐츠
     * @return decryptStr 복호화된 문자열
     * @throws Exception
     */
    public String decrypt(String key, String initialVector, String contents) throws Exception {

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);

        SecretKeySpec secretKeySpec = new SecretKeySpec(hexToBytes(key), "AES");
        IvParameterSpec ivParameterSpec = new IvParameterSpec(hexToBytes(initialVector));

        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivParameterSpec);

        byte[] decodedBytes = Base64.getDecoder().decode(contents);
        byte[] decrypted = cipher.doFinal(decodedBytes);

        return new String(decrypted, "UTF-8");

    }

    /**
     * 16진수 문자열을 바이트 배열로 변환하는 메소드
     *
     * @param hex 16진수 문자열
     * @return 바이트 배열
     */
    private byte[] hexToBytes(String hex) {
        byte[] b;

        if(hex == null) {
            return null;
        }

        int len = hex.length();
        if(len%2 == 1) {
            return null;
        }

        b = new byte[len / 2];

        for(int i = 0; i < len; i += 2) {
            b[i >> 1] = (byte)Integer.parseInt(hex.substring(i, i + 2), 16);
        }

        return b;
    }

//    public static void main(String[] args) throws Exception	{
//
//        try {
//
//            String str = "{\"results\": [{\"referent\": \"dd95828f-2055-4548-99b9-9d881a80cfbd\", \"schema_id\": \"N6r4nLwAkcYUX8c8Kb8Ufu:2:CertificateOfTOEIC:4.0\", \"cred_def_id\": \"DrLbXFSao4Vo8gMfjxPxU1:3:CL:1617698238:81df0010-62b4-45b1-bd00-8d0ad74762fd\", \"rev_reg_id\": \"DrLbXFSao4Vo8gMfjxPxU1:4:DrLbXFSao4Vo8gMfjxPxU1:3:CL:1617698238:81df0010-62b4-45b1-bd00-8d0ad74762fd:CL_ACCUM:ba0c3ff0-00c0-40dc-8ee6-d6a1916542cf\", \"cred_rev_id\": \"11\", \"attrs\": {\"score_of_reading\": \"\", \"registration_number\": \"123456789-987654321\", \"exp_date\": \"20230228\", \"score_of_total\": \"990\", \"score_of_listening\": \"117\", \"date_of_birth\": \"19901001\", \"date_of_test\": \"20190105\", \"english_name\": \"ethan\", \"korean_name\": \"\\uae40\\uc99d\\uba85\"}}, {\"referent\": \"0922d433-c478-461f-a9e0-2b17b4853573\", \"schema_id\": \"cU8rErjgKj8fgn1kTDren:2:PersonIdentityCredential:1.0\", \"cred_def_id\": \"TmisnEAGBPeVVDjtAXPdYt:3:CL:0:v01\", \"rev_reg_id\": null, \"cred_rev_id\": null, \"attrs\": {\"mobile_num\": \"01023456789\", \"person_name\": \"\\uae40\\uc99d\\uba85\", \"telecom\": \"SKT\", \"date_of_birth\": \"19901001\", \"exp_date\": \"20230627\", \"gender\": \"M\", \"ci\": \"\", \"is_foreigner\": \"N\"}}]}";
//
//            Aes256Util aes256Util = new Aes256Util();
//
//            String encode = aes256Util.encrypt("9c91204af6be527d2a04d3599eba4176", "1aa0c49d92ce06a12d6916a2582b3c58", str);
//            System.out.println(encode);
//            String plain = aes256Util.decrypt("9c91204af6be527d2a04d3599eba4176", "1aa0c49d92ce06a12d6916a2582b3c58", encode);
//            System.out.println(plain);
//
//            String testStr = "zRTmpLMkF/sOcoJkbMrpjIpumI7zJymo5v4Dy1LEmfCcDB4mWOfsyiECdSqL81jdgwlkF2y+zrqi+qIs4ad4U5tdVUdLBALeRTHfwKZntCW0QBRTFFzq66lpW+1asPF69bJl1ns/Hrj4K2wQqSWMAOK2oFKzJA/85AKfDcidI+T0STZP4xc6sTuZRP3IOFOAPcxR9bqNTGrl8WHVWyBP7K9cCZSVbv8O6K8kBTtDgqxWJQkMSv7QUT1CcFKgkexz+5QzVPshHyel9nuPcMyMbp6OsLmoa/kweIpNVmloX6DPuPLlnoMM4K20qEbcGH2OaFKdHy/cRF7FUDC6OaCWB2YDpolYxeFfCKpiR6RdQ7h+z4uFFTfxUUgn5ek2n6ORy++Y4Rg+NgRnd/0EL8V0su0lEytbGVNVrzL7O2ges5UXryXV1KaRHXpl4MJWo9M/Jd5KWd0FfVOzOslx0tE5ya1/koGlld9OkDkkkOHb4Z84DMZnjU4PHTzGGBspLu98UJS62JdFTkm//gw3w1W7YZ5Xwag786w3hq22I66Jb2HGsoOYJ3HvzjmGZm08AatoceUpJTJKXTKfvtSz/OYC3JiEpjnkoAhu5VE/sHeG88AoZ06tf8qIGORxq01KiYqbu2iwjlMC0eMtJPpzkh0ufXAsbYd1mgN2AJzk04eOCFulbyq3iFJycpiaZVxXkyVyR8YbHpZN7iEHprDCfeQFVBdqjhezCq0R+TJTWGFSGFIfMqUNN3yz5bF+d212TY6l6eGaOGnDPSkBFcUIL4OD/VS6vnZMxaM3Ynta8XwJO05Ro4H06h95tr77sjrvt+1JF42WZE9xzv/nVngeFEslbDgAEqHDOwOF7Okwv2Q+L2zJFI2m+HOGewr4Y4E3a051HZBK35oYsnr+q+kQY/mBefK+MzghBdytbyLR+pikj446YrAa0sFkLofi3ttYOmHKmD6wx/rca1Jn3TsDELaPkxDj4RJWmEFh1pIJCJFVuZdcFc5cUKGOmLeKRydtCB4TXDrFkxbrRhMWacTliQabUZms7n/ijRnUDcMr8dmQxuJjQDkIAo1NrmJNz6b+bFf48U/Xcb7KvDyTB7MvPKofOP6WzxWj8SViIwzV6OPD0M+Bo8iweb176tC9/bC/Ih7S8Xaq4PINyEXhomtRkcpfbD2/LrgWz22Fvo6bE8MxGXKV0SmAUA5Qk5tYATcxH71XRxs6Onj87GynVHmjpTBqBM445i1j/jVDrtIgzpspZnZ8VhGwet2mRZzZEfEwhsav5F09x5/onJ1NxzmzzcUUgGoaTMXa38pg37BofxlnF+fOEobm5tE7Yigp+sOO6aXOfCJSgX/L7TSuns8JEzMPXAGCeVftlojB+4ZKhZ35hO+DCIUbLGE4ko9tKJNj3SSTY6BRAj1ezZYSg2pPpcHdg6H+cgNQV5R9XMhvPt0PZu65LVoxbxBlw/ZRq2BrPY1s";
//            String testStrResult = aes256Util.decrypt("9c91204af6be527d2a04d3599eba4176", "1aa0c49d92ce06a12d6916a2582b3c58", testStr);
//            System.out.println(testStrResult);
//
//        } catch (Exception e) {
//            System.out.println(e.getMessage());
//        }
//    }

}
