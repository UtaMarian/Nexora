package io.nexora.acl;

import java.util.EnumSet;
import java.util.Set;

/**
 * SharePoint's default permission levels. Each level is a strict superset of the one before it,
 * so the effective level of a user is simply the highest level granted to them or their groups.
 */
public enum PermissionLevel {
    READ(EnumSet.of(Right.VIEW)),
    CONTRIBUTE(EnumSet.of(Right.VIEW, Right.ADD_ITEMS, Right.EDIT_ITEMS, Right.DELETE_ITEMS)),
    EDIT(EnumSet.of(Right.VIEW, Right.ADD_ITEMS, Right.EDIT_ITEMS, Right.DELETE_ITEMS, Right.MANAGE_LISTS)),
    FULL_CONTROL(EnumSet.allOf(Right.class));

    private final Set<Right> rights;

    PermissionLevel(Set<Right> rights) {
        this.rights = rights;
    }

    public boolean grants(Right right) {
        return rights.contains(right);
    }

    public Set<Right> rights() {
        return rights;
    }

    public static PermissionLevel max(PermissionLevel a, PermissionLevel b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
