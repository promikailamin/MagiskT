/**
 * Internal sepol_impl class declaration.
 * Wraps libsepol's policydb_t and provides methods for adding,
 * removing, and querying SELinux rules (AV rules, type rules,
 * xperms, genfscon, filename_trans). This is the backend
 * implementation that SePolicy delegates to.
 *
 * NOTE: Internal API — do not use directly outside this module.
 */
#pragma once

// Internal APIs, do not use directly

#include <map>
#include <string_view>
#include <rust/cxx.h>

#include <sepol/policydb/policydb.h>

using Str = rust::Str;

struct Xperm;

/**
 * Internal SELinux policy implementation wrapping a libsepol policydb.
 *
 * Owns a `policydb *` (allocated externally) and provides the full set
 * of rule manipulation primitives. Rules are stored in the avtab (AV rules
 * and xperms), the filename_trans hash table, and the genfs linked list.
 */
class sepol_impl {
    /** Find an existing avtab node matching the key and optional xperms */
    avtab_ptr_t find_avtab_node(avtab_key_t *key, avtab_extended_perms_t *xperms);
    /** Insert a new avtab node with the given key, initializing its data field */
    avtab_ptr_t insert_avtab_node(avtab_key_t *key);
    /** Find or create an avtab node for the given key and xperms */
    avtab_ptr_t get_avtab_node(avtab_key_t *key, avtab_extended_perms_t *xperms);

    /** Print a type or attribute declaration to the given FILE stream */
    void print_type(FILE *fp, type_datum_t *type);
    /** Print an AV rule (allow/auditallow/dontaudit/type/xperm) to the given FILE stream */
    void print_avtab(FILE *fp, avtab_ptr_t node);
    /** Print a filename_trans rule to the given FILE stream */
    void print_filename_trans(FILE *fp, hashtab_ptr_t node);

    /**
     * Add an AV rule by string names, resolving them to internal type/class/perm objects.
     * @param s source type name (empty = wildcard)
     * @param t target type name (empty = wildcard)
     * @param c class name (empty = wildcard)
     * @param p permission name (empty = all perms)
     * @param effect avtab effect (AVTAB_ALLOWED, AVTAB_AUDITALLOW, AVTAB_AUDITDENY)
     * @param invert if true, clear permission bits instead of setting them
     */
    bool add_rule(Str s, Str t, Str c, Str p, int effect, bool invert);

    /** Add an AV rule using resolved internal type/class/perm pointers */
    void add_rule(type_datum_t *src, type_datum_t *tgt, class_datum_t *cls, perm_datum_t *perm, int effect, bool invert);

    /** Add an xperm rule using resolved internal type/class pointers */
    void add_xperm_rule(type_datum_t *src, type_datum_t *tgt, class_datum_t *cls, const Xperm &p, int effect);

    /** Add an xperm rule by string names, resolving types and class */
    bool add_xperm_rule(Str s, Str t, Str c, const Xperm &p, int effect);

    /** Add a type rule (type_transition/type_change/type_member) by string names */
    bool add_type_rule(Str s, Str t, Str c, Str d, int effect);

    /** Add a filename-based type_transition rule (type_transition with a filename) */
    bool add_filename_trans(Str s, Str t, Str c, Str d, Str o);

    /** Add a genfscon filesystem-label mapping rule */
    bool add_genfscon(Str fs_name, Str path, Str context);

    /**
     * Add a new type or attribute to the policy.
     * @param type_name the name of the new type
     * @param flavor TYPE_TYPE for a regular type, TYPE_ATTRIB for an attribute
     */
    bool add_type(Str type_name, uint32_t flavor);

    /** Set a type's permissive flag in the permissive map */
    bool set_type_state(Str type_name, bool permissive);

    /** Assign a type to an attribute by updating type_attr_map and attr_type_map */
    void add_typeattribute(type_datum_t *type, type_datum_t *attr);

    /** Assign a type to an attribute by string names */
    bool add_typeattribute(Str type, Str attr);

    /** The libsepol policydb being manipulated */
    policydb *db;

    /**
     * Cache mapping class names to their permission name arrays.
     * Lazily populated by print_avtab for pretty-printing.
     * Indexed by class name, value is an array of up to 32 permission name pointers.
     */
    std::map<std::string_view, std::array<const char *, 32>> class_perm_names;

    friend struct SePolicy;

public:
    /** Wrap an existing policydb (does NOT take ownership of allocation, but ~sepol_impl frees it) */
    sepol_impl(policydb *db) : db(db) {}

    /** Destructor: calls policydb_destroy and frees the db pointer */
    ~sepol_impl();
};
