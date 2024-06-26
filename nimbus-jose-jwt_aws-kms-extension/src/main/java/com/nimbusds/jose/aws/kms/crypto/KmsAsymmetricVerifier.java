/*
  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.

  Licensed under the Apache License, Version 2.0 (the "License").
  You may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */

package com.nimbusds.jose.aws.kms.crypto;


import com.amazonaws.services.kms.AWSKMS;
import com.amazonaws.services.kms.model.DependencyTimeoutException;
import com.amazonaws.services.kms.model.DisabledException;
import com.amazonaws.services.kms.model.InvalidGrantTokenException;
import com.amazonaws.services.kms.model.InvalidKeyUsageException;
import com.amazonaws.services.kms.model.KMSInternalException;
import com.amazonaws.services.kms.model.KMSInvalidSignatureException;
import com.amazonaws.services.kms.model.KMSInvalidStateException;
import com.amazonaws.services.kms.model.KeyUnavailableException;
import com.amazonaws.services.kms.model.MessageType;
import com.amazonaws.services.kms.model.NotFoundException;
import com.amazonaws.services.kms.model.VerifyRequest;
import com.amazonaws.services.kms.model.VerifyResult;
import com.nimbusds.jose.CriticalHeaderParamsAware;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.RemoteKeySourceException;
import com.nimbusds.jose.aws.kms.crypto.impl.KmsAsymmetricSigningCryptoProvider;
import com.nimbusds.jose.aws.kms.exceptions.TemporaryJOSEException;
import com.nimbusds.jose.crypto.impl.CriticalHeaderParamsDeferral;
import com.nimbusds.jose.util.Base64URL;
import java.nio.ByteBuffer;
import java.util.Set;
import javax.annotation.concurrent.ThreadSafe;
import lombok.NonNull;
import lombok.var;

/**
 * Sign verifier implementation for asymmetric signing with public/private key stored in AWS KMS.
 * <p>
 * See {@link KmsAsymmetricSigningCryptoProvider} for supported algorithms, and for details of various
 * constructor parameters.
 */
@ThreadSafe
public class KmsAsymmetricVerifier
        extends KmsAsymmetricSigningCryptoProvider
        implements JWSVerifier, CriticalHeaderParamsAware {

    /**
     * The critical header policy.
     */
    private final CriticalHeaderParamsDeferral critPolicy = new CriticalHeaderParamsDeferral();


    public KmsAsymmetricVerifier(
            @NonNull final AWSKMS kms, @NonNull final String privateKeyId, @NonNull final MessageType messageType) {
        super(kms, privateKeyId, messageType);
    }


    public KmsAsymmetricVerifier(
            @NonNull final AWSKMS kms, @NonNull String privateKeyId, @NonNull final MessageType messageType,
            @NonNull final Set<String> defCritHeaders) {
        super(kms, privateKeyId, messageType);
        critPolicy.setDeferredCriticalHeaderParams(defCritHeaders);
    }


    @Override
    public Set<String> getProcessedCriticalHeaderParams() {

        return critPolicy.getProcessedCriticalHeaderParams();
    }


    @Override
    public Set<String> getDeferredCriticalHeaderParams() {

        return critPolicy.getDeferredCriticalHeaderParams();
    }


    @Override
    public boolean verify(
            @NonNull final JWSHeader header, @NonNull final byte[] signedContent, @NonNull final Base64URL signature)
            throws JOSEException {

        if (!critPolicy.headerPasses(header)) {
            return false;
        }

        var message = getMessage(header, signedContent);

        VerifyResult verifyResult;
        try {
            verifyResult = getKms().verify(new VerifyRequest()
                    .withKeyId(getPrivateKeyId())
                    .withSigningAlgorithm(JWS_ALGORITHM_TO_SIGNING_ALGORITHM_SPEC.get(header.getAlgorithm()).toString())
                    .withMessageType(getMessageType())
                    .withMessage(message)
                    .withSignature(ByteBuffer.wrap(signature.decode())));
        } catch (KMSInvalidSignatureException e) {
            return false;
        } catch (NotFoundException | DisabledException | KeyUnavailableException | InvalidKeyUsageException
                | KMSInvalidStateException e) {
            throw new RemoteKeySourceException("An exception was thrown from KMS due to invalid key.", e);
        } catch (DependencyTimeoutException | InvalidGrantTokenException | KMSInternalException e) {
            throw new TemporaryJOSEException("A temporary exception was thrown from KMS.", e);
        }

        return verifyResult.isSignatureValid();
    }

}
