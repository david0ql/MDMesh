package com.hmdm.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class EnrollmentCodesTest {

    @Test
    public void generated_codes_use_the_alphabet_and_round_trip() {
        for (int i = 0; i < 200; i++) {
            String c = EnrollmentCodes.generate();
            assertEquals(EnrollmentCodes.LENGTH, c.length());
            assertEquals(c, EnrollmentCodes.normalize(EnrollmentCodes.display(c)));
            assertEquals(c, EnrollmentCodes.normalize(" " + c.toLowerCase().substring(0, 4) + " - " + c.substring(4) + " "));
        }
    }

    @Test
    public void look_alikes_and_wrong_lengths_are_not_codes() {
        assertNull(EnrollmentCodes.normalize("ABCD-EFG0"));
        assertNull(EnrollmentCodes.normalize("ABCD-EFGI"));
        assertNull(EnrollmentCodes.normalize("ABCDEFG"));
        assertNull(EnrollmentCodes.normalize("3f1a8c2e-uuid-token"));
        assertNull(EnrollmentCodes.normalize(null));
        assertEquals("ABCD-EFGH", EnrollmentCodes.display("ABCDEFGH"));
    }
}
