/*
 * Copyright (C) 2012-2013 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2010-2014 Thialfihar <thi@thialfihar.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sufficientlysecure.keychain.pgp;

import android.content.Context;

import org.spongycastle.bcpg.CompressionAlgorithmTags;
import org.spongycastle.bcpg.HashAlgorithmTags;
import org.spongycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.spongycastle.bcpg.sig.KeyFlags;
import org.spongycastle.jce.provider.BouncyCastleProvider;
import org.spongycastle.jce.spec.ElGamalParameterSpec;
import org.spongycastle.openpgp.*;
import org.spongycastle.openpgp.PGPUtil;
import org.spongycastle.openpgp.operator.PBESecretKeyDecryptor;
import org.spongycastle.openpgp.operator.PBESecretKeyEncryptor;
import org.spongycastle.openpgp.operator.PGPContentSignerBuilder;
import org.spongycastle.openpgp.operator.PGPDigestCalculator;
import org.spongycastle.openpgp.operator.jcajce.*;
import org.thialfihar.android.apg.Constants;
import org.thialfihar.android.apg.Id;
import org.thialfihar.android.apg.R;
import org.thialfihar.android.apg.pgp.exception.PgpGeneralException;
import org.thialfihar.android.apg.provider.ProviderHelper;
import org.thialfihar.android.apg.util.IterableIterator;
import org.thialfihar.android.apg.util.Log;
import org.thialfihar.android.apg.util.Primes;
import org.thialfihar.android.apg.util.ProgressDialogUpdater;

import java.io.IOException;
import java.math.BigInteger;
import java.security.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.List;
import java.util.TimeZone;

public class PgpKeyOperation {
    private Context mContext;
    private ProgressDialogUpdater mProgress;

    private static final int[] PREFERRED_SYMMETRIC_ALGORITHMS = new int[]{
            SymmetricKeyAlgorithmTags.AES_256, SymmetricKeyAlgorithmTags.AES_192,
            SymmetricKeyAlgorithmTags.AES_128, SymmetricKeyAlgorithmTags.CAST5,
            SymmetricKeyAlgorithmTags.TRIPLE_DES};
    private static final int[] PREFERRED_HASH_ALGORITHMS = new int[]{HashAlgorithmTags.SHA1,
            HashAlgorithmTags.SHA256, HashAlgorithmTags.RIPEMD160};
    private static final int[] PREFERRED_COMPRESSION_ALGORITHMS = new int[]{
            CompressionAlgorithmTags.ZLIB, CompressionAlgorithmTags.BZIP2,
            CompressionAlgorithmTags.ZIP};

    public PgpKeyOperation(Context context, ProgressDialogUpdater progress) {
        super();
        this.mContext = context;
        this.mProgress = progress;
    }

    public void updateProgress(int message, int current, int total) {
        if (mProgress != null) {
            mProgress.setProgress(message, current, total);
        }
    }

    public void updateProgress(int current, int total) {
        if (mProgress != null) {
            mProgress.setProgress(current, total);
        }
    }

    /**
     * Creates new secret key.
     *
     * @param algorithmChoice
     * @param keySize
     * @param passphrase
     * @param isMasterKey
     * @return
     * @throws NoSuchAlgorithmException
     * @throws PGPException
     * @throws NoSuchProviderException
     * @throws PgpGeneralException
     * @throws InvalidAlgorithmParameterException
     */

    // TODO: key flags?
    public PGPSecretKey createKey(int algorithmChoice, int keySize, String passphrase,
                                  boolean isMasterKey)
            throws NoSuchAlgorithmException, PGPException, NoSuchProviderException,
                   PgpGeneralException, InvalidAlgorithmParameterException {

        if (keySize < 512) {
            throw new PgpGeneralException(mContext.getString(R.string.error_key_size_minimum512bit));
        }

        if (passphrase == null) {
            passphrase = "";
        }

        int algorithm = 0;
        KeyPairGenerator keyGen = null;

        switch (algorithmChoice) {
            case Id.choice.algorithm.dsa: {
                keyGen = KeyPairGenerator.getInstance("DSA", Constants.BOUNCY_CASTLE_PROVIDER_NAME);
                keyGen.initialize(keySize, new SecureRandom());
                algorithm = PGPPublicKey.DSA;
                break;
            }

            case Id.choice.algorithm.elgamal: {
                if (isMasterKey) {
                    throw new PgpGeneralException(
                            mContext.getString(R.string.error_master_key_must_not_be_el_gamal));
                }
                keyGen = KeyPairGenerator.getInstance("ElGamal", Constants.BOUNCY_CASTLE_PROVIDER_NAME);
                BigInteger p = Primes.getBestPrime(keySize);
                BigInteger g = new BigInteger("2");

                ElGamalParameterSpec elParams = new ElGamalParameterSpec(p, g);

                keyGen.initialize(elParams);
                algorithm = PGPPublicKey.ELGAMAL_ENCRYPT;
                break;
            }

            case Id.choice.algorithm.rsa: {
                keyGen = KeyPairGenerator.getInstance("RSA", Constants.BOUNCY_CASTLE_PROVIDER_NAME);
                keyGen.initialize(keySize, new SecureRandom());

                algorithm = PGPPublicKey.RSA_GENERAL;
                break;
            }

            default: {
                throw new PgpGeneralException(
                        mContext.getString(R.string.error_unknown_algorithm_choice));
            }
        }

        // build new key pair
        PGPKeyPair keyPair = new JcaPGPKeyPair(algorithm, keyGen.generateKeyPair(), new Date());

        // define hashing and signing algos
        PGPDigestCalculator sha1Calc = new JcaPGPDigestCalculatorProviderBuilder().build().get(
                HashAlgorithmTags.SHA1);

        // Build key encrypter and decrypter based on passphrase
        PBESecretKeyEncryptor keyEncryptor = new JcePBESecretKeyEncryptorBuilder(
                PGPEncryptedData.CAST5, sha1Calc)
                .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(passphrase.toCharArray());

        PGPSecretKey secKey = new PGPSecretKey(keyPair.getPrivateKey(), keyPair.getPublicKey(),
                sha1Calc, isMasterKey, keyEncryptor);

        return secKey;
    }

    public void changeSecretKeyPassphrase(PGPSecretKeyRing keyRing, String oldPassPhrase,
                                          String newPassPhrase) throws IOException, PGPException,
            NoSuchProviderException {

        updateProgress(R.string.progress_building_key, 0, 100);
        if (oldPassPhrase == null) {
            oldPassPhrase = "";
        }
        if (newPassPhrase == null) {
            newPassPhrase = "";
        }

        PGPSecretKeyRing newKeyRing = PGPSecretKeyRing.copyWithNewPassword(
                keyRing,
                new JcePBESecretKeyDecryptorBuilder(new JcaPGPDigestCalculatorProviderBuilder()
                        .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME).build()).setProvider(
                        Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(oldPassPhrase.toCharArray()),
                new JcePBESecretKeyEncryptorBuilder(keyRing.getSecretKey()
                        .getKeyEncryptionAlgorithm()).build(newPassPhrase.toCharArray()));

        updateProgress(R.string.progress_saving_key_ring, 50, 100);

        ProviderHelper.saveKeyRing(mContext, newKeyRing);

        updateProgress(R.string.progress_done, 100, 100);

    }

    public Pair<PGPSecretKeyRing, PGPPublicKeyRing> buildNewSecretKey(
            SaveKeyringParcel saveParcel)
            throws PgpGeneralMsgIdException, PGPException, SignatureException, IOException {

        int usageId = saveParcel.keysUsages.get(0);
        boolean canSign;
        String mainUserId = saveParcel.userIds.get(0);

        PGPSecretKey masterKey = saveParcel.keys.get(0);

        if (oldPassPhrase == null) {
            oldPassPhrase = "";
        }
        if (newPassPhrase == null) {
            newPassPhrase = "";
        }

        PBESecretKeyDecryptor keyDecryptor = new JcePBESecretKeyDecryptorBuilder().setProvider(
                Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(saveParcel.oldPassphrase.toCharArray());
        PGPPrivateKey masterPrivateKey = masterKey.extractPrivateKey(keyDecryptor);

                    // there was an old self-signed certificate for this uid
                    if(foundSelfSign)
                        continue;

        for (String userId : saveParcel.userIds) {
            PGPContentSignerBuilder signerBuilder = new JcaPGPContentSignerBuilder(
                    masterPublicKey.getAlgorithm(), HashAlgorithmTags.SHA1)
                    .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME);
            PGPSignatureGenerator sGen = new PGPSignatureGenerator(signerBuilder);

                    PGPContentSignerBuilder signerBuilder = new JcaPGPContentSignerBuilder(
                            masterPublicKey.getAlgorithm(), HashAlgorithmTags.SHA1)
                            .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME);
                    PGPSignatureGenerator sGen = new PGPSignatureGenerator(signerBuilder);

                    sGen.init(PGPSignature.POSITIVE_CERTIFICATION, masterPrivateKey);

                    PGPSignature certification = sGen.generateCertification(userId, masterPublicKey);

                    masterPublicKey = PGPPublicKey.addCertification(masterPublicKey, userId, certification);
                }

                masterKeyPair = new PGPKeyPair(masterPublicKey, masterPrivateKey);
            }

            PGPSignatureSubpacketGenerator hashedPacketsGen;
            PGPSignatureSubpacketGenerator unhashedPacketsGen; {

        if (saveParcel.keysExpiryDates.get(0) != null) {
            GregorianCalendar creationDate = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            creationDate.setTime(masterPublicKey.getCreationTime());
            GregorianCalendar expiryDate = saveParcel.keysExpiryDates.get(0);
            //note that the below, (a/c) - (b/c) is *not* the same as (a - b) /c
            //here we purposefully ignore partial days in each date - long type has no fractional part!
            long numDays = (expiryDate.getTimeInMillis() / 86400000) -
                (creationDate.getTimeInMillis() / 86400000);
            if (numDays <= 0) {
                throw new PgpGeneralMsgIdException(R.string.error_expiry_must_come_after_creation);
            }

            updateProgress(R.string.progress_building_master_key, 30, 100);

            // define hashing and signing algos
            PGPDigestCalculator sha1Calc = new JcaPGPDigestCalculatorProviderBuilder().build().get(
                    HashAlgorithmTags.SHA1);
            PGPContentSignerBuilder certificationSignerBuilder = new JcaPGPContentSignerBuilder(
                    masterKeyPair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA1);

        // Build key encrypter based on passphrase
        PBESecretKeyEncryptor keyEncryptor = new JcePBESecretKeyEncryptorBuilder(
                PGPEncryptedData.CAST5, sha1Calc)
                .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(
                        saveParcel.newPassphrase.toCharArray());

            keyGen = new PGPKeyRingGenerator(PGPSignature.POSITIVE_CERTIFICATION,
                    masterKeyPair, mainUserId, sha1Calc, hashedPacketsGen.generate(),
                    unhashedPacketsGen.generate(), certificationSignerBuilder, keyEncryptor);

        }

        updateProgress(R.string.progress_adding_sub_keys, 40, 100);

        for (int i = 1; i < saveParcel.keys.size(); ++i) {
            updateProgress(40 + 40 * (i - 1) / (saveParcel.keys.size() - 1), 100);

            PGPSecretKey subKey = saveParcel.keys.get(i);
            PGPPublicKey subPublicKey = subKey.getPublicKey();

            PBESecretKeyDecryptor keyDecryptor = new JcePBESecretKeyDecryptorBuilder()
                    .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(
                            saveParcel.oldPassphrase.toCharArray());
            PGPPrivateKey subPrivateKey = subKey.extractPrivateKey(keyDecryptor2);

            // TODO: now used without algorithm and creation time?! (APG 1)
            PGPKeyPair subKeyPair = new PGPKeyPair(subPublicKey, subPrivateKey);

            PGPSignatureSubpacketGenerator hashedPacketsGen = new PGPSignatureSubpacketGenerator();
            PGPSignatureSubpacketGenerator unhashedPacketsGen = new PGPSignatureSubpacketGenerator();

            int keyFlags = 0;

            usageId = saveParcel.keysUsages.get(i);
            canSign = (usageId & KeyFlags.SIGN_DATA) > 0; //todo - separate function for this
            if (canSign) {
                Date todayDate = new Date(); //both sig times the same
                keyFlags |= KeyFlags.SIGN_DATA;
                // cross-certify signing keys
                hashedPacketsGen.setSignatureCreationTime(false, todayDate); //set outer creation time
                PGPSignatureSubpacketGenerator subHashedPacketsGen = new PGPSignatureSubpacketGenerator();
                subHashedPacketsGen.setSignatureCreationTime(false, todayDate); //set inner creation time
                PGPContentSignerBuilder signerBuilder = new JcaPGPContentSignerBuilder(
                        subPublicKey.getAlgorithm(), PGPUtil.SHA1)
                        .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME);
                PGPSignatureGenerator sGen = new PGPSignatureGenerator(signerBuilder);
                sGen.init(PGPSignature.PRIMARYKEY_BINDING, subPrivateKey);
                sGen.setHashedSubpackets(subHashedPacketsGen.generate());
                PGPSignature certification = sGen.generateCertification(masterPublicKey,
                        subPublicKey);
                unhashedPacketsGen.setEmbeddedSignature(false, certification);
            }
            if (canEncrypt) {
                keyFlags |= KeyFlags.ENCRYPT_COMMS | KeyFlags.ENCRYPT_STORAGE;
            }
            hashedPacketsGen.setKeyFlags(false, keyFlags);

            if (saveParcel.keysExpiryDates.get(i) != null) {
                GregorianCalendar creationDate = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
                creationDate.setTime(subPublicKey.getCreationTime());
                GregorianCalendar expiryDate = saveParcel.keysExpiryDates.get(i);
                //note that the below, (a/c) - (b/c) is *not* the same as (a - b) /c
                //here we purposefully ignore partial days in each date - long type has no fractional part!
                long numDays =
                        (expiryDate.getTimeInMillis() / 86400000) - (creationDate.getTimeInMillis() / 86400000);
                if (numDays <= 0) {
                    throw new PgpGeneralException
                            (mContext.getString(R.string.error_expiry_must_come_after_creation));
                }
                hashedPacketsGen.setKeyExpirationTime(false, numDays * 86400);
            } else {
                //do this explicitly, although since we're rebuilding,
                hashedPacketsGen.setKeyExpirationTime(false, 0);
                //this happens anyway
            }

            keyGen.addSubKey(subKeyPair, hashedPacketsGen.generate(), unhashedPacketsGen.generate());
        }

        PGPSecretKeyRing secretKeyRing = keyGen.generateSecretKeyRing();
        PGPPublicKeyRing publicKeyRing = keyGen.generatePublicKeyRing();

        return new Pair<PGPSecretKeyRing, PGPPublicKeyRing>(secretKeyRing, publicKeyRing);

    }

    public Pair<PGPSecretKeyRing, PGPPublicKeyRing> buildSecretKey(PGPSecretKeyRing mKR,
                                                                   PGPPublicKeyRing pKR,
                                                                   SaveKeyringParcel saveParcel)
            throws PgpGeneralMsgIdException, PGPException, SignatureException, IOException {

        updateProgress(R.string.progress_building_key, 0, 100);
        PGPSecretKey masterKey = saveParcel.keys.get(0);

        if (saveParcel.oldPassphrase == null) {
            saveParcel.oldPassphrase = "";
        }
        if (saveParcel.newPassphrase == null) {
            saveParcel.newPassphrase = "";
        }

        /*
        IDs - NB This might not need to happen later, if we change the way the primary ID is chosen
            remove deleted ids
            if the primary ID changed we need to:
                remove all of the IDs from the keyring, saving their certifications
                add them all in again, updating certs of IDs which have changed
            else
                remove changed IDs and add in with new certs

            if the master key changed, we need to remove the primary ID certification, so we can add
            the new one when it is generated, and they don't conflict

        Keys
            remove deleted keys
            if a key is modified, re-sign it
                do we need to remove and add in?

        Todo
            identify more things which need to be preserved - e.g. trust levels?
                    user attributes
        */

        if (saveParcel.deletedKeys != null) {
            for (PGPSecretKey dKey : saveParcel.deletedKeys) {
                mKR = PGPSecretKeyRing.removeSecretKey(mKR, dKey);
            }
        }
        publicKeyRing = PGPPublicKeyRing.insertPublicKey(publicKeyRing, pubkey);

        masterKey = mKR.getSecretKey();
        PGPPublicKey masterPublicKey = masterKey.getPublicKey();

        int usageId = saveParcel.keysUsages.get(0);
        boolean canSign;
        String mainUserId = saveParcel.userIds.get(0);

        PBESecretKeyDecryptor keyDecryptor = new JcePBESecretKeyDecryptorBuilder().setProvider(
                Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(saveParcel.oldPassphrase.toCharArray());
        PGPPrivateKey masterPrivateKey = masterKey.extractPrivateKey(keyDecryptor);

        updateProgress(R.string.progress_certifying_master_key, 20, 100);

        boolean anyIDChanged = false;
        for (String delID : saveParcel.deletedIDs) {
            anyIDChanged = true;
            masterPublicKey = PGPPublicKey.removeCertification(masterPublicKey, delID);
        }

        int userIDIndex = 0;

        PGPSignatureSubpacketGenerator hashedPacketsGen = new PGPSignatureSubpacketGenerator();
        PGPSignatureSubpacketGenerator unhashedPacketsGen = new PGPSignatureSubpacketGenerator();

        hashedPacketsGen.setKeyFlags(true, usageId);

        hashedPacketsGen.setPreferredSymmetricAlgorithms(true, PREFERRED_SYMMETRIC_ALGORITHMS);
        hashedPacketsGen.setPreferredHashAlgorithms(true, PREFERRED_HASH_ALGORITHMS);
        hashedPacketsGen.setPreferredCompressionAlgorithms(true, PREFERRED_COMPRESSION_ALGORITHMS);

        if (saveParcel.keysExpiryDates.get(0) != null) {
            GregorianCalendar creationDate = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
            creationDate.setTime(masterPublicKey.getCreationTime());
            GregorianCalendar expiryDate = saveParcel.keysExpiryDates.get(0);
            //note that the below, (a/c) - (b/c) is *not* the same as (a - b) /c
            //here we purposefully ignore partial days in each date - long type has no fractional part!
            long numDays = (expiryDate.getTimeInMillis() / 86400000) -
                (creationDate.getTimeInMillis() / 86400000);
            if (numDays <= 0) {
                throw new PgpGeneralMsgIdException(R.string.error_expiry_must_come_after_creation);
            }
            hashedPacketsGen.setKeyExpirationTime(false, numDays * 86400);
        } else {
            hashedPacketsGen.setKeyExpirationTime(false, 0);
            // do this explicitly, although since we're rebuilding,
            // this happens anyway
        }

        if (saveParcel.primaryIDChanged ||
            !saveParcel.originalIDs.get(0).equals(saveParcel.userIds.get(0))) {
            anyIDChanged = true;
            ArrayList<Pair<String, PGPSignature>> sigList = new ArrayList<Pair<String, PGPSignature>>();
            for (String userId : saveParcel.userIds) {
                String origID = saveParcel.originalIDs.get(userIDIndex);
                if (origID.equals(userId) && !saveParcel.newIDs[userIDIndex] &&
                    !userId.equals(saveParcel.originalPrimaryID) && userIDIndex != 0) {
                    Iterator<PGPSignature> origSigs = masterPublicKey.getSignaturesForID(origID);
                    // TODO: make sure this iterator only has signatures we are interested in
                    while (origSigs.hasNext()) {
                        PGPSignature origSig = origSigs.next();
                        sigList.add(new Pair<String, PGPSignature>(origID, origSig));
                    }
                } else {
                    PGPContentSignerBuilder signerBuilder = new JcaPGPContentSignerBuilder(
                            masterPublicKey.getAlgorithm(), HashAlgorithmTags.SHA1)
                            .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME);
                    PGPSignatureGenerator sGen = new PGPSignatureGenerator(signerBuilder);

                    sGen.init(PGPSignature.POSITIVE_CERTIFICATION, masterPrivateKey);
                    if (userIDIndex == 0) {
                        sGen.setHashedSubpackets(hashedPacketsGen.generate());
                        sGen.setUnhashedSubpackets(unhashedPacketsGen.generate());
                    }
                    PGPSignature certification = sGen.generateCertification(userId, masterPublicKey);
                    sigList.add(new Pair<String, PGPSignature>(userId, certification));
                }
                if (!saveParcel.newIDs[userIDIndex]) {
                    masterPublicKey = PGPPublicKey.removeCertification(masterPublicKey, origID);
                }
                userIDIndex++;
            }
            for (Pair<String, PGPSignature> toAdd : sigList) {
                masterPublicKey =
                    PGPPublicKey.addCertification(masterPublicKey, toAdd.first, toAdd.second);
            }
        } else {
            for (String userId : saveParcel.userIds) {
                String origID = saveParcel.originalIDs.get(userIDIndex);
                if (!origID.equals(userId) || saveParcel.newIDs[userIDIndex]) {
                    anyIDChanged = true;
                    PGPContentSignerBuilder signerBuilder = new JcaPGPContentSignerBuilder(
                            masterPublicKey.getAlgorithm(), HashAlgorithmTags.SHA1)
                            .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME);
                    PGPSignatureGenerator sGen = new PGPSignatureGenerator(signerBuilder);

                    sGen.init(PGPSignature.POSITIVE_CERTIFICATION, masterPrivateKey);
                    if (userIDIndex == 0) {
                        sGen.setHashedSubpackets(hashedPacketsGen.generate());
                        sGen.setUnhashedSubpackets(unhashedPacketsGen.generate());
                    }
                    PGPSignature certification = sGen.generateCertification(userId, masterPublicKey);
                    if (!saveParcel.newIDs[userIDIndex]) {
                        masterPublicKey = PGPPublicKey.removeCertification(masterPublicKey, origID);
                    }
                    masterPublicKey =
                        PGPPublicKey.addCertification(masterPublicKey, userId, certification);
                }
                userIDIndex++;
            }
        }

        ArrayList<Pair<String, PGPSignature>> sigList = new ArrayList<Pair<String, PGPSignature>>();
        if (saveParcel.moddedKeys[0]) {
            userIDIndex = 0;
            for (String userId : saveParcel.userIds) {
                String origID = saveParcel.originalIDs.get(userIDIndex);
                if (!(origID.equals(saveParcel.originalPrimaryID) && !saveParcel.primaryIDChanged)) {
                    Iterator<PGPSignature> sigs = masterPublicKey.getSignaturesForID(userId);
                    // TODO: make sure this iterator only has signatures we are interested in
                    while (sigs.hasNext()) {
                        PGPSignature sig = sigs.next();
                        sigList.add(new Pair<String, PGPSignature>(userId, sig));
                    }
                }
                masterPublicKey = PGPPublicKey.removeCertification(masterPublicKey, userId);
                userIDIndex++;
            }
            anyIDChanged = true;
        }

        //update the keyring with the new ID information
        if (anyIDChanged) {
            pKR = PGPPublicKeyRing.insertPublicKey(pKR, masterPublicKey);
            mKR = PGPSecretKeyRing.replacePublicKeys(mKR, pKR);
        }

        PGPKeyPair masterKeyPair = new PGPKeyPair(masterPublicKey, masterPrivateKey);

        updateProgress(R.string.progress_building_master_key, 30, 100);

        // define hashing and signing algos
        PGPDigestCalculator sha1Calc = new JcaPGPDigestCalculatorProviderBuilder().build().get(
                HashAlgorithmTags.SHA1);
        PGPContentSignerBuilder certificationSignerBuilder = new JcaPGPContentSignerBuilder(
                masterKeyPair.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA1);

        // Build key encryptor based on old passphrase, as some keys may be unchanged
        PBESecretKeyEncryptor keyEncryptor = new JcePBESecretKeyEncryptorBuilder(
                PGPEncryptedData.CAST5, sha1Calc)
                .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(
                        saveParcel.oldPassphrase.toCharArray());

        //this generates one more signature than necessary...
        PGPKeyRingGenerator keyGen = new PGPKeyRingGenerator(PGPSignature.POSITIVE_CERTIFICATION,
                masterKeyPair, mainUserId, sha1Calc, hashedPacketsGen.generate(),
                unhashedPacketsGen.generate(), certificationSignerBuilder, keyEncryptor);

        for (int i = 1; i < saveParcel.keys.size(); ++i) {
            updateProgress(40 + 50 * i / saveParcel.keys.size(), 100);
            if (saveParcel.moddedKeys[i]) {
                PGPSecretKey subKey = saveParcel.keys.get(i);
                PGPPublicKey subPublicKey = subKey.getPublicKey();

                PBESecretKeyDecryptor keyDecryptor2;
                if (saveParcel.newKeys[i]) {
                    keyDecryptor2 = new JcePBESecretKeyDecryptorBuilder()
                            .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(
                                    "".toCharArray());
                } else {
                    keyDecryptor2 = new JcePBESecretKeyDecryptorBuilder()
                            .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(
                                    saveParcel.oldPassphrase.toCharArray());
                }
                PGPPrivateKey subPrivateKey = subKey.extractPrivateKey(keyDecryptor2);
                PGPKeyPair subKeyPair = new PGPKeyPair(subPublicKey, subPrivateKey);

                hashedPacketsGen = new PGPSignatureSubpacketGenerator();
                unhashedPacketsGen = new PGPSignatureSubpacketGenerator();

                usageId = saveParcel.keysUsages.get(i);
                canSign = (usageId & KeyFlags.SIGN_DATA) > 0; //todo - separate function for this
                if (canSign) {
                    Date todayDate = new Date(); //both sig times the same
                    // cross-certify signing keys
                    hashedPacketsGen.setSignatureCreationTime(false, todayDate); //set outer creation time
                    PGPSignatureSubpacketGenerator subHashedPacketsGen = new PGPSignatureSubpacketGenerator();
                    subHashedPacketsGen.setSignatureCreationTime(false, todayDate); //set inner creation time
                    PGPContentSignerBuilder signerBuilder = new JcaPGPContentSignerBuilder(
                            subPublicKey.getAlgorithm(), PGPUtil.SHA1)
                            .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME);
                    PGPSignatureGenerator sGen = new PGPSignatureGenerator(signerBuilder);
                    sGen.init(PGPSignature.PRIMARYKEY_BINDING, subPrivateKey);
                    sGen.setHashedSubpackets(subHashedPacketsGen.generate());
                    PGPSignature certification = sGen.generateCertification(masterPublicKey,
                            subPublicKey);
                    unhashedPacketsGen.setEmbeddedSignature(false, certification);
                }
                hashedPacketsGen.setKeyFlags(false, usageId);

                if (saveParcel.keysExpiryDates.get(i) != null) {
                    GregorianCalendar creationDate = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
                    creationDate.setTime(subPublicKey.getCreationTime());
                    GregorianCalendar expiryDate = saveParcel.keysExpiryDates.get(i);
                    // note that the below, (a/c) - (b/c) is *not* the same as (a - b) /c
                    // here we purposefully ignore partial days in each date - long type has
                    // no fractional part!
                    long numDays = (expiryDate.getTimeInMillis() / 86400000) -
                        (creationDate.getTimeInMillis() / 86400000);
                    if (numDays <= 0) {
                        throw new PgpGeneralMsgIdException(R.string.error_expiry_must_come_after_creation);
                    }
                    hashedPacketsGen.setKeyExpirationTime(false, numDays * 86400);
                } else {
                    hashedPacketsGen.setKeyExpirationTime(false, 0);
                    // do this explicitly, although since we're rebuilding,
                    // this happens anyway
                }

                keyGen.addSubKey(subKeyPair, hashedPacketsGen.generate(), unhashedPacketsGen.generate());
                // certifications will be discarded if the key is changed, because I think, for a start,
                // they will be invalid. Binding certs are regenerated anyway, and other certs which
                // need to be kept are on IDs and attributes
                // TODO: don't let revoked keys be edited, other than removed - changing one would
                // result in the revocation being wrong?
            }
        }

        PGPSecretKeyRing updatedSecretKeyRing = keyGen.generateSecretKeyRing();
        //finally, update the keyrings
        Iterator<PGPSecretKey> itr = updatedSecretKeyRing.getSecretKeys();
        while (itr.hasNext()) {
            PGPSecretKey theNextKey = itr.next();
            if ((theNextKey.isMasterKey() && saveParcel.moddedKeys[0]) || !theNextKey.isMasterKey()) {
                mKR = PGPSecretKeyRing.insertSecretKey(mKR, theNextKey);
                pKR = PGPPublicKeyRing.insertPublicKey(pKR, theNextKey.getPublicKey());
            }
        }

        //replace lost IDs
        if (saveParcel.moddedKeys[0]) {
            masterPublicKey = mKR.getPublicKey();
            for (Pair<String, PGPSignature> toAdd : sigList) {
                masterPublicKey = PGPPublicKey.addCertification(masterPublicKey, toAdd.first, toAdd.second);
            }
            pKR = PGPPublicKeyRing.insertPublicKey(pKR, masterPublicKey);
            mKR = PGPSecretKeyRing.replacePublicKeys(mKR, pKR);
        }

        // Build key encryptor based on new passphrase
        PBESecretKeyEncryptor keyEncryptorNew = new JcePBESecretKeyEncryptorBuilder(
                PGPEncryptedData.CAST5, sha1Calc)
                .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(
                        saveParcel.newPassphrase.toCharArray());

        //update the passphrase
        mKR = PGPSecretKeyRing.copyWithNewPassword(mKR, keyDecryptor, keyEncryptorNew);

        /* additional handy debug info
        Log.d(Constants.TAG, " ------- in private key -------");
        for(String uid : new IterableIterator<String>(secretKeyRing.getPublicKey().getUserIDs())) {
            for(PGPSignature sig : new IterableIterator<PGPSignature>(secretKeyRing.getPublicKey().getSignaturesForID(uid))) {
                Log.d(Constants.TAG, "sig: " + PgpKeyHelper.convertKeyIdToHex(sig.getKeyID()) + " for " + uid);
            }
        }
        Log.d(Constants.TAG, " ------- in public key -------");
        for(String uid : new IterableIterator<String>(publicKeyRing.getPublicKey().getUserIDs())) {
            for(PGPSignature sig : new IterableIterator<PGPSignature>(publicKeyRing.getPublicKey().getSignaturesForID(uid))) {
                Log.d(Constants.TAG, "sig: " + PgpKeyHelper.convertKeyIdToHex(sig.getKeyID()) + " for " + uid);
            }
        }
        */

        ProviderHelper.saveKeyRing(mContext, secretKeyRing);
        ProviderHelper.saveKeyRing(mContext, publicKeyRing);

        updateProgress(R.string.progress_done, 100, 100);
    }

    /**
     * Certify the given pubkeyid with the given masterkeyid.
     *
     * @param masterKeyId Certifying key, must be available as secret key
     * @param pubKeyId ID of public key to certify
     * @param userIds User IDs to certify, must not be null or empty
     * @param passphrase Passphrase of the secret key
     * @return A keyring with added certifications
     */
    public PGPPublicKeyRing certifyKey(long masterKeyId, long pubKeyId, List<String> userIds, String passphrase)
            throws PgpGeneralException, NoSuchAlgorithmException, NoSuchProviderException,
            PGPException, SignatureException {
        if (passphrase == null) {
            throw new PgpGeneralException("Unable to obtain passphrase");
        } else {

            // create a signatureGenerator from the supplied masterKeyId and passphrase
            PGPSignatureGenerator signatureGenerator; {

                PGPSecretKey certificationKey = PgpKeyHelper.getCertificationKey(mContext, masterKeyId);
                if (certificationKey == null) {
                    throw new PgpGeneralException(mContext.getString(R.string.error_signature_failed));
                }

                PBESecretKeyDecryptor keyDecryptor = new JcePBESecretKeyDecryptorBuilder().setProvider(
                        Constants.BOUNCY_CASTLE_PROVIDER_NAME).build(passphrase.toCharArray());
                PGPPrivateKey signaturePrivateKey = certificationKey.extractPrivateKey(keyDecryptor);
                if (signaturePrivateKey == null) {
                    throw new PgpGeneralException(
                            mContext.getString(R.string.error_could_not_extract_private_key));
                }

                // TODO: SHA256 fixed?
                JcaPGPContentSignerBuilder contentSignerBuilder = new JcaPGPContentSignerBuilder(
                        certificationKey.getPublicKey().getAlgorithm(), PGPUtil.SHA256)
                        .setProvider(Constants.BOUNCY_CASTLE_PROVIDER_NAME);

                signatureGenerator = new PGPSignatureGenerator(contentSignerBuilder);
                signatureGenerator.init(PGPSignature.DEFAULT_CERTIFICATION, signaturePrivateKey);
            }

            { // supply signatureGenerator with a SubpacketVector
                PGPSignatureSubpacketGenerator spGen = new PGPSignatureSubpacketGenerator();
                PGPSignatureSubpacketVector packetVector = spGen.generate();
                signatureGenerator.setHashedSubpackets(packetVector);
            }

            // fetch public key ring, add the certification and return it
            PGPPublicKeyRing pubring = ProviderHelper
                    .getPGPPublicKeyRingByKeyId(mContext, pubKeyId);
            PGPPublicKey signedKey = pubring.getPublicKey(pubKeyId);
            for(String userId : new IterableIterator<String>(userIds.iterator())) {
                PGPSignature sig = signatureGenerator.generateCertification(userId, signedKey);
                signedKey = PGPPublicKey.addCertification(signedKey, userId, sig);
            }
            pubring = PGPPublicKeyRing.insertPublicKey(pubring, signedKey);

            return pubring;
        }
    }
}