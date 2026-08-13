package com.kvstore.raft;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.assertj.core.api.Assertions.assertThat;

class PersistentRaftLogTest {

    @TempDir
    Path tempDir;

    @Test
    void entriesSurviveReopen() {
        Path logPath = tempDir.resolve("raft.log");

        RaftLog first = new RaftLog(logPath);
        first.append(1, "one".getBytes(), "command-1");
        first.append(1, "two".getBytes(), "command-2");

        RaftLog reopened = new RaftLog(logPath);

        assertThat(reopened.lastIndex()).isEqualTo(2);
        assertThat(new String(reopened.get(1).command())).isEqualTo("one");
        assertThat(reopened.get(2).commandId()).isEqualTo("command-2");
    }

    @Test
    void incompleteTailIsDiscardedDuringRecovery() throws Exception {
        Path logPath = tempDir.resolve("raft.log");

        RaftLog log = new RaftLog(logPath);
        log.append(1, "valid".getBytes(), "command-1");
        long validLogSize = Files.size(logPath);
        Files.write(logPath, new byte[]{0, 0, 0, 32, 1, 2, 3},
                StandardOpenOption.APPEND);

        RaftLog recovered = new RaftLog(logPath);

        assertThat(recovered.lastIndex()).isEqualTo(1);
        assertThat(new String(recovered.get(1).command())).isEqualTo("valid");
        assertThat(Files.size(logPath)).isEqualTo(validLogSize);
    }
}