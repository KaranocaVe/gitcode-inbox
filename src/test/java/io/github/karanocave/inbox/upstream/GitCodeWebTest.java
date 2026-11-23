package io.github.karanocave.inbox.upstream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitCodeWebTest {
    @Test
    void passwordEncodingMatchesTheGitCodeProtocolForUnicode() {
        assertThat(GitCodeWeb.encodePassword("fixture密码!42"))
                .isEqualTo("gcx68wjivMkWI48fkSeEukqFOORrMYRhTcOcWZHsNCM=");
    }
}
