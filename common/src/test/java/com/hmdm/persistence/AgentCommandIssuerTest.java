package com.hmdm.persistence;

import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.User;
import com.hmdm.security.RequestOrigin;
import com.hmdm.security.SecurityContext;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AgentCommandIssuerTest {

    @After public void cleanUp() {
        SecurityContext.release();
        RequestOrigin.clear();
    }

    private static User user(int id, String login) {
        User u = new User();
        u.setId(id);
        u.setLogin(login);
        u.setCustomerId(1);
        return u;
    }

    @Test public void a_console_request_stamps_the_user_and_the_address() {
        SecurityContext.init(user(42, "ana"));
        RequestOrigin.set("190.0.0.7");
        AgentCommand c = new AgentCommand();

        AgentCommandDAO.stampIssuer(c);

        assertEquals(Integer.valueOf(42), c.getIssuerId());
        assertEquals("ana", c.getIssuerName());
        assertEquals("190.0.0.7", c.getIssuerIp());
    }

    @Test public void a_command_nobody_signed_in_queued_belongs_to_the_system() {
        AgentCommand c = new AgentCommand();
        AgentCommandDAO.stampIssuer(c);
        assertEquals(AgentCommand.SYSTEM_ISSUER, c.getIssuerName());
        assertNull(c.getIssuerId());
        assertNull(c.getIssuerIp());
    }

    @Test public void a_device_request_with_only_a_customer_is_the_system_too() {
        SecurityContext.init(Integer.valueOf(1));
        RequestOrigin.set("10.0.0.1");
        AgentCommand c = new AgentCommand();
        AgentCommandDAO.stampIssuer(c);
        assertEquals(AgentCommand.SYSTEM_ISSUER, c.getIssuerName());
        assertNull(c.getIssuerIp());
    }

    @Test public void an_issuer_set_by_the_caller_is_kept() {
        SecurityContext.init(user(42, "ana"));
        AgentCommand c = new AgentCommand();
        c.setIssuerName("programado");
        AgentCommandDAO.stampIssuer(c);
        assertEquals("programado", c.getIssuerName());
        assertNull(c.getIssuerId());
    }

    @Test public void a_user_without_login_is_still_identified() {
        SecurityContext.init(user(9, " "));
        AgentCommand c = new AgentCommand();
        AgentCommandDAO.stampIssuer(c);
        assertEquals("usuario 9", c.getIssuerName());
    }
}
