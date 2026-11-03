package io.nexora.acl;

/** Individual rights checked by services. Permission levels bundle these. */
public enum Right {
    /** See the object and open/download it. */
    VIEW,
    /** Upload files, create folders. */
    ADD_ITEMS,
    /** Change files, metadata, rename, check out/in. */
    EDIT_ITEMS,
    /** Delete to the recycle bin, move out. */
    DELETE_ITEMS,
    /** Create/delete libraries, change library settings and columns. */
    MANAGE_LISTS,
    /** Change permissions and inheritance. */
    MANAGE_PERMISSIONS,
    /** Change site settings, manage site groups, see the site's full recycle bin and audit log. */
    MANAGE_WEB,
    /** Discard or check in someone else's checkout. */
    OVERRIDE_CHECKOUT
}
