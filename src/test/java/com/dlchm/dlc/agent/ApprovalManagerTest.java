package com.dlchm.dlc.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ApprovalManagerTest {
    @Test
    void approvalCompletesAWaitingRequest() throws Exception {
        ApprovalManager manager = new ApprovalManager();
        ApprovalManager.Request request = manager.request("session", "bash_execute", "rm file");
        Thread approver = new Thread(() -> {
            try { Thread.sleep(20); } catch (InterruptedException ignored) { }
            manager.approve(request.id());
        });
        approver.start();
        assertEquals(ApprovalManager.Decision.APPROVED,
                manager.await(request.id(), () -> false));
        approver.join();
        assertTrue(manager.pending("session").isEmpty());
    }
}
