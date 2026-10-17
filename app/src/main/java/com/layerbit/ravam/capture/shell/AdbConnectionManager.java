package com.layerbit.ravam.capture.shell;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.Random;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.PrivateKeyUsageExtension;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

/**
 * Ravam's own ADB identity, persisted in app storage. This is what lets the app pair
 * with the phone's Wireless Debugging and reach the shell UID without any companion app.
 * The key pair and self-signed certificate are generated once and reused.
 */
public final class AdbConnectionManager extends AbsAdbConnectionManager {

    private static volatile AdbConnectionManager instance;

    public static AdbConnectionManager getInstance(Context context) throws Exception {
        if (instance == null) {
            synchronized (AdbConnectionManager.class) {
                if (instance == null) instance = new AdbConnectionManager(context.getApplicationContext());
            }
        }
        return instance;
    }

    private PrivateKey privateKey;
    private Certificate certificate;

    private AdbConnectionManager(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);

        File dir = new File(context.getFilesDir(), "adb");
        if (!dir.exists()) dir.mkdirs();
        File keyFile = new File(dir, "key.pk8");
        File certFile = new File(dir, "cert.pem");

        if (keyFile.exists() && certFile.exists()) {
            privateKey = loadPrivateKey(keyFile);
            certificate = loadCertificate(certFile);
        } else {
            generate(keyFile, certFile);
        }
    }

    @NonNull @Override protected PrivateKey getPrivateKey() { return privateKey; }
    @NonNull @Override protected Certificate getCertificate() { return certificate; }
    @NonNull @Override protected String getDeviceName() { return "Ravam"; }

    // ---- key + certificate -------------------------------------------------

    private void generate(File keyFile, File certFile) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();
        PublicKey publicKey = keyPair.getPublic();
        privateKey = keyPair.getPrivate();

        String subject = "CN=Ravam";
        String algorithmName = "SHA512withRSA";
        long expiry = System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000; // ~10 years

        CertificateExtensions extensions = new CertificateExtensions();
        extensions.set("SubjectKeyIdentifier",
                new SubjectKeyIdentifierExtension(new KeyIdentifier(publicKey).getIdentifier()));

        X500Name x500Name = new X500Name(subject);
        Date notBefore = new Date();
        Date notAfter = new Date(expiry);
        extensions.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));
        CertificateValidity validity = new CertificateValidity(notBefore, notAfter);

        X509CertInfo info = new X509CertInfo();
        info.set("version", new CertificateVersion(2));
        info.set("serialNumber", new CertificateSerialNumber(new Random().nextInt() & Integer.MAX_VALUE));
        info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithmName)));
        info.set("subject", new CertificateSubjectName(x500Name));
        info.set("key", new CertificateX509Key(publicKey));
        info.set("validity", validity);
        info.set("issuer", new CertificateIssuerName(x500Name));
        info.set("extensions", extensions);

        X509CertImpl cert = new X509CertImpl(info);
        cert.sign(privateKey, algorithmName);
        certificate = cert;

        try (FileOutputStream k = new FileOutputStream(keyFile)) {
            k.write(new PKCS8EncodedKeySpec(privateKey.getEncoded()).getEncoded());
        }
        try (FileOutputStream c = new FileOutputStream(certFile)) {
            c.write(certificate.getEncoded());
        }
    }

    private static PrivateKey loadPrivateKey(File f) throws Exception {
        byte[] bytes = readAll(f);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
    }

    private static Certificate loadCertificate(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static byte[] readAll(File f) throws Exception {
        byte[] out = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0, n;
            while (off < out.length && (n = in.read(out, off, out.length - off)) > 0) off += n;
        }
        return out;
    }
}
