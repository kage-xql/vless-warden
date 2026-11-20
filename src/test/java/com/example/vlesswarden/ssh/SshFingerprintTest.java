package com.example.vlesswarden.ssh;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SshFingerprintTest {

    /**
     * Референсный RSA-ключ. Эталонный fingerprint OpenSSH сверен с `ssh-keygen -lf`:
     * реализация sshd (`KeyUtils.getFingerPrint`) должна дать точно такой же
     * результат, чтобы на панель приземлялись пользователи с привычными
     * отпечатками, а панель не переставала узнавать реальный сервер.
     */
    @Test
    void getFingerPrintMatchesOpenSsh() throws Exception {
        var key = KeyUtils.loadPublicKey(Path.of("src/test/resources/ssh-fingerprint.pub"));
        assertThat(KeyUtils.getFingerPrint(key))
                .isEqualTo("SHA256:8YeCCfH2sOrtOwmgjGfoIGSxihB7HKIgGuT4Qbt8Y/A");
    }
}
