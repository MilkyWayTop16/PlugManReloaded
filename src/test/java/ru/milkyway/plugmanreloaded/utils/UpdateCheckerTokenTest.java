package ru.milkyway.plugmanreloaded.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckerTokenTest {

    @Test
    @DisplayName("UpdateChecker uses getGithubToken and passes authorization header to HttpJson.get")
    void updateCheckerRetrievesTokenAndPassesAuthHeader() throws Exception {
        ClassReader reader = new ClassReader(UpdateChecker.class.getName());
        List<String> invokedMethods = new ArrayList<>();

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        invokedMethods.add(owner + "." + name + descriptor);
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }
                };
            }
        }, 0);

        boolean callsGetGithubToken = invokedMethods.stream()
                .anyMatch(m -> m.contains("getGithubToken"));
        boolean callsHttpJsonWithAuth = invokedMethods.stream()
                .anyMatch(m -> m.contains("HttpJson.get") && m.contains("(Ljava/lang/String;Ljava/lang/String;)"));

        assertTrue(callsGetGithubToken, "UpdateChecker must retrieve github-token from ConfigManager");
        assertTrue(callsHttpJsonWithAuth, "UpdateChecker must call HttpJson.get(url, authorization)");
    }
}
