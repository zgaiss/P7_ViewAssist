package com.xiaopeng.qa.camera;

public final class RecoveryBudgetTest {
    private static int assertions;
    private static void check(boolean value) { assertions++; if (!value) throw new AssertionError(assertions); }
    public static void main(String[] args) {
        RecoveryBudget b = new RecoveryBudget();
        check(b.used() == 0);
        check(b.admit("a", RecoveryBudget.Kind.OPEN_RETRY, "", 100));
        check(!b.admit("a", RecoveryBudget.Kind.CONFIGURE_RETRY, "", 200)); // same event not another kind
        check(b.used() == 1 && b.contains("a"));
        check(b.admit("b", RecoveryBudget.Kind.OPEN_RETRY, "", 200));
        check(b.admit("c", RecoveryBudget.Kind.OPEN_RETRY, "", 300));
        check(!b.admit("d", RecoveryBudget.Kind.OPEN_RETRY, "", Long.MAX_VALUE)); // time never replenishes
        check(b.admit("e", RecoveryBudget.Kind.CONFIGURE_RETRY, "", 400));
        check(b.admit("f", RecoveryBudget.Kind.RELAY_REBUILD, "", 500));
        check(b.used() == 5);
        check(!b.admit("g", RecoveryBudget.Kind.CONFIGURE_RETRY, "", 600)); // shared cap beats subcap
        RecoveryBudget output = new RecoveryBudget();
        check(output.admit("p1", RecoveryBudget.Kind.OUTPUT_REPLACE, "P", 100));
        check(output.admit("p2", RecoveryBudget.Kind.OUTPUT_REPLACE, "P", 200));
        check(!output.admit("p3", RecoveryBudget.Kind.OUTPUT_REPLACE, "P", 30099));
        check(output.admit("p3", RecoveryBudget.Kind.OUTPUT_REPLACE, "P", 30100));
        check(!output.admit("p4", RecoveryBudget.Kind.OUTPUT_REPLACE, "P", 30199));
        check(output.admit("s1", RecoveryBudget.Kind.OUTPUT_REPLACE, "S", 30100));
        check(output.admit("r1", RecoveryBudget.Kind.RELAY_REBUILD, "", 30100));
        check(!output.admit("p4", RecoveryBudget.Kind.OUTPUT_REPLACE, "P", 30200));
        check(!output.admit("r2", RecoveryBudget.Kind.RELAY_REBUILD, "", 30100));
        RecoveryBudget config = new RecoveryBudget();
        check(config.admit("c1", RecoveryBudget.Kind.CONFIGURE_RETRY, "", 1));
        check(config.admit("c2", RecoveryBudget.Kind.CONFIGURE_RETRY, "", 2));
        check(!config.admit("c3", RecoveryBudget.Kind.CONFIGURE_RETRY, "", 3));
        check(config.used() == 2);
        System.out.println("RecoveryBudgetTest: " + assertions + " assertions passed");
    }
}
