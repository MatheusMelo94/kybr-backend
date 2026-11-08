package com.matheusmelo.kybr.config;

import com.matheusmelo.kybr.service.storage.S3DocumentStorage;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsWebIdentityTokenFileCredentialsProvider;

/**
 * Live document storage ({@code kybr.storage.mode=s3}): the private bucket through the AWS SDK's S3
 * client over the JDK HTTP client, with connect and whole-call timeouts.
 *
 * <p>Credentials: the platform's identity token exchanged for the configured role
 * ({@code AssumeRoleWithWebIdentity}; the platform writes the token to the file named by
 * {@code AWS_WEB_IDENTITY_TOKEN_FILE}), so there are no long-lived keys; or, as the documented
 * fallback, static keys scoped to the bucket prefix. Never logged: the settings' {@code toString}
 * hides the keys, and storage failures carry a status only.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "kybr.storage", name = "mode", havingValue = "s3")
public class S3Config {

    static final String SESSION_NAME = "kybr-api";
    static final String TOKEN_FILE_ENV = "AWS_WEB_IDENTITY_TOKEN_FILE";
    static final String DEFAULT_TOKEN_FILE = "/.fly/oidc_token";

    @Bean(destroyMethod = "close")
    S3Client s3Client(KybrProperties properties) {
        return s3Client(properties.storage());
    }

    @Bean
    S3DocumentStorage s3DocumentStorage(S3Client s3, KybrProperties properties, MeterRegistry meters) {
        KybrProperties.Storage storage = properties.storage();
        return new S3DocumentStorage(s3, new S3DocumentStorage.Bucket(storage.bucket(), storage.kmsKeyId()),
                new S3DocumentStorage.Bucket(storage.quarantineBucket(), storage.quarantineKmsKeyId()), meters);
    }

    /** The S3 client for {@code storage}; path-style addressing when an endpoint override is set (tests). */
    public static S3Client s3Client(KybrProperties.Storage storage) {
        var builder = S3Client.builder()
                .region(Region.of(storage.region()))
                .credentialsProvider(credentials(storage))
                .httpClient(httpClient(storage))
                .overrideConfiguration(override -> override.apiCallTimeout(storage.callTimeout()));
        if (storage.endpoint() != null) {
            builder.endpointOverride(storage.endpoint()).forcePathStyle(true);
        }
        return builder.build();
    }

    /** Static keys when both are set; otherwise the web-identity role. */
    public static AwsCredentialsProvider credentials(KybrProperties.Storage storage) {
        if (storage.accessKeyId() != null) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(storage.accessKeyId(), storage.secretAccessKey()));
        }
        StsClient sts = StsClient.builder()
                .region(Region.of(storage.region()))
                .httpClient(httpClient(storage))
                .overrideConfiguration(override -> override.apiCallTimeout(storage.callTimeout()))
                .build();
        Path tokenFile = Path.of(Objects.requireNonNullElse(System.getenv(TOKEN_FILE_ENV), DEFAULT_TOKEN_FILE));
        return StsWebIdentityTokenFileCredentialsProvider.builder()
                .stsClient(sts)
                .roleArn(storage.roleArn())
                .roleSessionName(SESSION_NAME)
                .webIdentityTokenFile(tokenFile)
                .build();
    }

    private static SdkHttpClient httpClient(KybrProperties.Storage storage) {
        return UrlConnectionHttpClient.builder()
                .connectionTimeout(storage.connectTimeout())
                .socketTimeout(storage.callTimeout())
                .build();
    }
}
