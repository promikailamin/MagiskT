/**
 * Low-level SELinux policy manipulation implementation.
 * Implements avtab node management (find/insert/remove/get),
 * rule addition (AV, xperm, type, filename_trans, genfscon),
 * type creation, typeattribute management, dontaudit stripping,
 * and policy rule pretty-printing.
 *
 * The `strip_av` macro controls the inversion logic: when inverting
 * (deny/dontaudit), auditdeny entries are stripped; otherwise they are kept.
 */
#include <base.hpp>

#include "include/sepolicy.hpp"

using namespace std;

/**
 * Invert is adding rules for auditdeny; in other cases, invert is removing rules.
 * For AVTAB_AUDITDENY, strip when (invert == true). For other effects, strip when (invert == false).
 */
#define strip_av(effect, invert) ((effect == AVTAB_AUDITDENY) == !invert)

// libsepol internal API declarations (not exposed in public headers)
__BEGIN_DECLS
int policydb_index_decls(sepol_handle_t * handle, policydb_t * p);
int avtab_hash(struct avtab_key *keyp, uint32_t mask);
int type_set_expand(type_set_t * set, ebitmap_t * t, policydb_t * p, unsigned char alwaysexpand);
int context_from_string(
        sepol_handle_t * handle,
        const policydb_t * policydb,
        context_struct_t ** cptr,
        const char *con_str, size_t con_str_len);
int context_to_string(
        sepol_handle_t * handle,
        const policydb_t * policydb,
        const context_struct_t * context,
        char **result, size_t * result_len);
__END_DECLS

/**
 * Wrapper that allows implicit conversion of T* to U* via static_cast.
 * Used to avoid verbose casts when converting between libsepol's
 * type-erased hashtab datum pointers and typed struct pointers.
 */
template <typename T>
struct auto_cast_wrapper {
    auto_cast_wrapper(T *ptr) : ptr(ptr) {}
    template <typename U>
    operator U*() const { return static_cast<U*>(ptr); }
private:
    T *ptr;
};

/** Factory function for auto_cast_wrapper */
template <typename T>
static auto_cast_wrapper<T> auto_cast(T *p) {
    return auto_cast_wrapper<T>(p);
}

/**
 * Copy a rust::Str into a fixed-size char array, null-terminating it.
 * @return number of bytes copied (excluding null terminator)
 */
template <size_t T>
static size_t copy_str(std::array<char, T> &dest, rust::Str src) {
    if (T == 0) return 0;
    size_t len = std::min(T - 1, src.size());
    memcpy(dest.data(), src.data(), len);
    dest[len] = '\0';
    return len;
}

/** Duplicate a rust::Str into a malloc-allocated null-terminated C string */
static char *dup_str(rust::Str src) {
    size_t len = src.size();
    char *s = static_cast<char *>(malloc(len + 1));
    memcpy(s, src.data(), len);
    s[len] = '\0';
    return s;
}

/** Compare a string_view with a rust::Str for equality */
static bool str_eq(string_view a, rust::Str b) {
    return a.size() == b.size() && memcmp(a.data(), b.data(), a.size()) == 0;
}

/**
 * Look up a key in a libsepol hashtable by rust::Str name.
 * The Str is copied into a fixed-size buffer for null-termination,
 * which hashtab_search requires.
 * @return the found datum, or nullptr
 */
static auto hashtab_find(hashtab_t h, Str key) {
    array<char, 256> buf{};
    copy_str(buf, key);
    return auto_cast(hashtab_search(h, buf.data()));
}

/** Iterate over a linked list, calling fn for each node */
template <class Node, class Func>
static void list_for_each(Node *node_ptr, const Func &fn) {
    auto cur = node_ptr;
    while (cur) {
        auto next = cur->next;
        fn(cur);
        cur = next;
    }
}

/** Find the first linked-list node matching the predicate */
template <class Node, class Func>
static Node *list_find(Node *node_ptr, const Func &fn) {
    for (auto cur = node_ptr; cur; cur = cur->next) {
        if (fn(cur)) {
            return cur;
        }
    }
    return nullptr;
}

/** Iterate over all entries in a hash table's bucket array */
template <class Node, class Func>
static void hash_for_each(Node **node_ptr, int n_slot, const Func &fn) {
    for (int i = 0; i < n_slot; ++i) {
        list_for_each(node_ptr[i], fn);
    }
}

/** Iterate over all entries in a libsepol hashtab_t */
template <class Func>
static void hashtab_for_each(hashtab_t htab, const Func &fn) {
    hash_for_each(htab->htable, htab->size, fn);
}

/** Iterate over all entries in a libsepol avtab (access vector table) */
template <class Func>
static void avtab_for_each(avtab_t *avtab, const Func &fn) {
    hash_for_each(avtab->htable, avtab->nslot, fn);
}

/** Execute fn for every attribute (TYPE_ATTRIB) in the given types hashtable */
template <class Func>
static void for_each_attr(hashtab_t htab, const Func &fn) {
    hashtab_for_each(htab, [&](hashtab_ptr_t node) {
        auto type = static_cast<type_datum_t *>(node->datum);
        if (type->flavor == TYPE_ATTRIB)
            fn(type);
    });
}

/**
 * Remove a single avtab node from the avtab hash table.
 * Walks the linked list to find and unlink the node, then frees its memory.
 * @return 0 on success, SEPOL_ENOMEM if hash table is null, SEPOL_ENOENT if not found
 */
static int avtab_remove_node(avtab_t *h, avtab_ptr_t node) {
    if (!h || !h->htable)
        return SEPOL_ENOMEM;

    // Compute hash bucket and walk the linked list to find the node
    int hvalue = avtab_hash(&node->key, h->mask);
    avtab_ptr_t prev = nullptr;
    avtab_ptr_t cur = h->htable[hvalue];
    while (cur) {
        if (cur == node)
            break;
        prev = cur;
        cur = cur->next;
    }
    if (cur == nullptr)
        return SEPOL_ENOENT;

    // Unlink from linked list
    if (prev)
        prev->next = node->next;
    else
        h->htable[hvalue] = node->next;
    h->nel--;

    // Free extended permissions if present, then the node itself
    free(node->datum.xperms);
    free(node);
    return 0;
}

/**
 * Check if an avtab node is redundant (i.e., has no effect).
 * - For AUDITDENY: redundant if all bits are set (~0U)
 * - For XPERMS: redundant if xperms is null
 * - For others: redundant if data is 0 (no permissions set)
 */
static bool is_redundant(avtab_ptr_t node) {
    switch (node->key.specified) {
    case AVTAB_AUDITDENY:
        return node->datum.data == ~0U;
    case AVTAB_XPERMS:
        return node->datum.xperms == nullptr;
    default:
        return node->datum.data == 0U;
    }
}

/**
 * Find an avtab node matching the given key and optional extended permissions.
 *
 * AVTAB_XPERMS entries are not necessarily unique — there may be multiple
 * entries with different xperms data for the same key. For xperm lookups,
 * we search for a node whose xperms match both the specified type and driver.
 * For non-xperm lookups we use a simple avtab_search_node.
 */
avtab_ptr_t sepol_impl::find_avtab_node(avtab_key_t *key, avtab_extended_perms_t *xperms) {
    avtab_ptr_t node;

    if (key->specified & AVTAB_XPERMS) {
        if (xperms == nullptr)
            return nullptr;
        node = avtab_search_node(&db->te_avtab, key);
        // Walk through all nodes with the same key to find matching xperms
        while (node) {
            if ((node->datum.xperms->specified == xperms->specified) &&
                (node->datum.xperms->driver == xperms->driver)) {
                node = nullptr;
                break;
            }
            node = avtab_search_node_next(node, key->specified);
        }
    } else {
        node = avtab_search_node(&db->te_avtab, key);
    }

    return node;
}

/**
 * Insert a new avtab node with the given key and an initialized data field.
 * AUDITDENY entries start with all bits set (~0U) since they use &= assignment.
 * All other entries start with 0U since they use |= assignment.
 */
avtab_ptr_t sepol_impl::insert_avtab_node(avtab_key_t *key) {
    avtab_datum_t avdatum{};
    avdatum.data = key->specified == AVTAB_AUDITDENY ? ~0U : 0U;
    return avtab_insert_nonunique(&db->te_avtab, key, &avdatum);
}

/** Find an existing avtab node, or insert one if it doesn't exist */
avtab_ptr_t sepol_impl::get_avtab_node(avtab_key_t *key, avtab_extended_perms_t *xperms) {
    avtab_ptr_t node = find_avtab_node(key, xperms);
    if (!node) {
        node = insert_avtab_node(key);
    }
    return node;
}

/**
 * Add an AV rule using resolved internal type/class/perm pointers.
 *
 * When source or target is nullptr (wildcard from expand), iterates over
 * all attributes or all types depending on whether we are stripping av.
 * When class is nullptr, iterates over all classes.
 *
 * The permission bit is set (|=) or cleared (&= ~) in the avtab datum,
 * depending on the `invert` flag. Redundant nodes (permissions == 0 or ~0)
 * are removed to keep the policy clean.
 */
void sepol_impl::add_rule(type_datum_t *src, type_datum_t *tgt, class_datum_t *cls, perm_datum_t *perm, int effect, bool invert) {
    // Wildcard source: iterate over all types or attributes
    if (src == nullptr) {
        if (strip_av(effect, invert)) {
            // Stripping: must iterate over ALL types for correctness
            hashtab_for_each(db->p_types.table, [&](hashtab_ptr_t node) {
                add_rule(auto_cast(node->datum), tgt, cls, perm, effect, invert);
            });
        } else {
            // Non-stripping: iterating over attributes is sufficient (types inherit)
            for_each_attr(db->p_types.table, [&](type_datum_t *type) {
                add_rule(type, tgt, cls, perm, effect, invert);
            });
        }
    } else if (tgt == nullptr) {
        // Wildcard target: same logic as wildcard source
        if (strip_av(effect, invert)) {
            hashtab_for_each(db->p_types.table, [&](hashtab_ptr_t node) {
                add_rule(src, auto_cast(node->datum), cls, perm, effect, invert);
            });
        } else {
            for_each_attr(db->p_types.table, [&](type_datum_t *type) {
                add_rule(src, type, cls, perm, effect, invert);
            });
        }
    } else if (cls == nullptr) {
        // Wildcard class: iterate over all classes
        hashtab_for_each(db->p_classes.table, [&](hashtab_ptr_t node) {
            add_rule(src, tgt, auto_cast(node->datum), perm, effect, invert);
        });
    } else {
        avtab_key_t key;
        key.source_type = src->s.value;
        key.target_type = tgt->s.value;
        key.target_class = cls->s.value;
        key.specified = effect;

        avtab_ptr_t node = get_avtab_node(&key, nullptr);
        if (invert) {
            // Clear permission bit
            if (perm)
                node->datum.data &= ~(1U << (perm->s.value - 1));
            else
                node->datum.data = 0U; // Clear all perms
        } else {
            // Set permission bit
            if (perm)
                node->datum.data |= 1U << (perm->s.value - 1);
            else
                node->datum.data = ~0U; // Set all perms
        }

        // Remove if the node is now redundant (no effective permissions)
        if (is_redundant(node))
            avtab_remove_node(&db->te_avtab, node);
    }
}

/**
 * Add an AV rule by string names.
 * Resolves source type, target type, class, and permission from the policy's
 * symbol tables. Empty strings act as wildcards (nullptr in the resolved call).
 * Returns false if any specified name is not found.
 */
bool sepol_impl::add_rule(Str s, Str t, Str c, Str p, int effect, bool invert) {
    type_datum_t *src = nullptr, *tgt = nullptr;
    class_datum_t *cls = nullptr;
    perm_datum_t *perm = nullptr;

    // Resolve source type
    if (!s.empty()) {
        src = hashtab_find(db->p_types.table, s);
        if (src == nullptr) {
            LOGW("source type %.*s does not exist\n", (int) s.size(), s.data());
            return false;
        }
    }

    // Resolve target type
    if (!t.empty()) {
        tgt = hashtab_find(db->p_types.table, t);
        if (tgt == nullptr) {
            LOGW("target type %.*s does not exist\n", (int) t.size(), t.data());
            return false;
        }
    }

    // Resolve class
    if (!c.empty()) {
        cls = hashtab_find(db->p_classes.table, c);
        if (cls == nullptr) {
            LOGW("class %.*s does not exist\n", (int) c.size(), c.data());
            return false;
        }
    }

    // Resolve permission (requires class to be specified)
    if (!p.empty()) {
        if (c.empty()) {
            LOGW("No class is specified, cannot add perm [%.*s] \n", (int) p.size(), p.data());
            return false;
        }

        perm = hashtab_find(cls->permissions.table, p);
        // Also check the common permission set (inherited permissions)
        if (perm == nullptr && cls->comdatum != nullptr) {
            perm = hashtab_find(cls->comdatum->permissions.table, p);
        }
        if (perm == nullptr) {
            LOGW("perm %.*s does not exist in class %.*s\n",
                 (int) p.size(), p.data(), (int) c.size(), c.data());
            return false;
        }
    }
    add_rule(src, tgt, cls, perm, effect, invert);
    return true;
}

/**
 * Extract the ioctl driver number from an ioctl command value.
 * The driver is bits 8-15 of the ioctl number.
 */
#define ioctl_driver(x) (x>>8 & 0xFF)

/**
 * Extract the ioctl function number from an ioctl command value.
 * The function is bits 0-7 of the ioctl number.
 */
#define ioctl_func(x) (x & 0xFF)

/**
 * Add an extended permissions rule (xperm) for ioctl command ranges.
 *
 * SELinux xperm rules track ioctl access using two node types per (src,tgt,cls) key:
 * - IOCTLDRIVER node: has a 256-bit perms map, one bit per driver number
 * - IOCTLFUNCTION nodes: up to 256 nodes (one per driver), each with a 256-bit function perms map
 *
 * The algorithm for non-reset (allow-like):
 *   - If the range spans multiple drivers: sets bits in the driver node's perms
 *   - If within one driver: creates/finds the function node and sets function bits
 *
 * For reset (deny-like), bits are cleared instead and driver/function perms
 * are filled with all-ones before clearing.
 */
void sepol_impl::add_xperm_rule(type_datum_t *src, type_datum_t *tgt, class_datum_t *cls, const Xperm &p, int effect) {
    if (db->policyvers < POLICYDB_VERSION_XPERMS_IOCTL) {
        LOGW("policy version %u does not support ioctl extended permissions rules\n", db->policyvers);
        return;
    }

    // Expand wildcards: iterate over attributes or classes
    if (src == nullptr) {
        for_each_attr(db->p_types.table, [&](type_datum_t *type) {
            add_xperm_rule(type, tgt, cls, p, effect);
        });
    } else if (tgt == nullptr) {
        for_each_attr(db->p_types.table, [&](type_datum_t *type) {
            add_xperm_rule(src, type, cls, p, effect);
        });
    } else if (cls == nullptr) {
        hashtab_for_each(db->p_classes.table, [&](hashtab_ptr_t node) {
            add_xperm_rule(src, tgt, auto_cast(node->datum), p, effect);
        });
    } else {
        avtab_key_t key;
        key.source_type = src->s.value;
        key.target_type = tgt->s.value;
        key.target_class = cls->s.value;
        key.specified = effect;

        // Collect existing nodes: driver node at index 256, function nodes at driver #
        avtab_ptr_t node_list[257] = { nullptr };
#define driver_node (node_list[256])

        for (avtab_ptr_t node = avtab_search_node(&db->te_avtab, &key); node;) {
            if (node->datum.xperms->specified == AVTAB_XPERMS_IOCTLDRIVER) {
                driver_node = node;
            } else if (node->datum.xperms->specified == AVTAB_XPERMS_IOCTLFUNCTION) {
                node_list[node->datum.xperms->driver] = node;
            }
            node = avtab_search_node_next(node, key.specified);
        }

        // If reset flag is set, clear existing function nodes and driver perms
        if (p.reset) {
            for (int i = 0; i <= 0xFF; ++i) {
                if (node_list[i]) {
                    avtab_remove_node(&db->te_avtab, node_list[i]);
                    node_list[i] = nullptr;
                }
            }
            if (driver_node) {
                memset(driver_node->datum.xperms->perms, 0, sizeof(avtab_extended_perms_t::perms));
            }
        }

        // Lambda: create a new IOCTLDRIVER node
        auto new_driver_node = [&]() -> avtab_ptr_t {
            auto node = insert_avtab_node(&key);
            node->datum.xperms = auto_cast(calloc(1, sizeof(avtab_extended_perms_t)));
            node->datum.xperms->specified = AVTAB_XPERMS_IOCTLDRIVER;
            node->datum.xperms->driver = 0;
            return node;
        };

        // Lambda: create a new IOCTLFUNCTION node for a given driver
        auto new_func_node = [&](uint8_t driver) -> avtab_ptr_t {
            auto node = insert_avtab_node(&key);
            node->datum.xperms = auto_cast(calloc(1, sizeof(avtab_extended_perms_t)));
            node->datum.xperms->specified = AVTAB_XPERMS_IOCTLFUNCTION;
            node->datum.xperms->driver = driver;
            return node;
        };

        if (!p.reset) {
            // Non-reset mode: SET permission bits
            if (ioctl_driver(p.low) != ioctl_driver(p.high)) {
                // Range spans multiple drivers: set bits in driver node
                if (driver_node == nullptr) {
                    driver_node = new_driver_node();
                }
                for (int i = ioctl_driver(p.low); i <= ioctl_driver(p.high); ++i) {
                    xperm_set(i, driver_node->datum.xperms->perms);
                }
            } else {
                // Single driver: set bits in the function node
                uint8_t driver = ioctl_driver(p.low);
                auto node = node_list[driver];
                if (node == nullptr) {
                    node = new_func_node(driver);
                    node_list[driver] = node;
                }
                for (int i = ioctl_func(p.low); i <= ioctl_func(p.high); ++i) {
                    xperm_set(i, node->datum.xperms->perms);
                }
            }
        } else {
            // Reset mode: CLEAR permission bits
            if (driver_node == nullptr) {
                driver_node = new_driver_node();
            }
            // Fill driver perms with all 1s first, then clear specific ranges
            memset(driver_node->datum.xperms->perms, ~0, sizeof(avtab_extended_perms_t::perms));

            if (ioctl_driver(p.low) != ioctl_driver(p.high)) {
                // Clear a range of driver bits
                for (int i = ioctl_driver(p.low); i <= ioctl_driver(p.high); ++i) {
                    xperm_clear(i, driver_node->datum.xperms->perms);
                }
            } else {
                uint8_t driver = ioctl_driver(p.low);
                auto node = node_list[driver];
                if (node == nullptr) {
                    node = new_func_node(driver);
                    // Fill function perms with all 1s first
                    memset(node->datum.xperms->perms, ~0, sizeof(avtab_extended_perms_t::perms));
                    node_list[driver] = node;
                }
                // Clear the driver bit (no driver-level access)
                xperm_clear(driver, driver_node->datum.xperms->perms);
                // Clear specific function bits
                for (int i = ioctl_func(p.low); i <= ioctl_func(p.high); ++i) {
                    xperm_clear(i, node->datum.xperms->perms);
                }
            }
        }
    }
}

/**
 * Add an xperm rule by string names.
 * Resolves source type, target type, and class; delegates to the typed version.
 * Returns false if any specified name is not found.
 */
bool sepol_impl::add_xperm_rule(Str s, Str t, Str c, const Xperm &p, int effect) {
    type_datum_t *src = nullptr, *tgt = nullptr;
    class_datum_t *cls = nullptr;

    if (!s.empty()) {
        src = hashtab_find(db->p_types.table, s);
        if (src == nullptr) {
            LOGW("source type %.*s does not exist\n", (int) s.size(), s.data());
            return false;
        }
    }

    if (!t.empty()) {
        tgt = hashtab_find(db->p_types.table, t);
        if (tgt == nullptr) {
            LOGW("target type %.*s does not exist\n", (int) t.size(), t.data());
            return false;
        }
    }

    if (!c.empty()) {
        cls = hashtab_find(db->p_classes.table, c);
        if (cls == nullptr) {
            LOGW("class %.*s does not exist\n", (int) c.size(), c.data());
            return false;
        }
    }

    add_xperm_rule(src, tgt, cls, p, effect);
    return true;
}

/**
 * Add a type rule (type_transition, type_change, or type_member) by string names.
 * Resolves source, target, class, and default type, then writes the default
 * type's value into the avtab datum.
 * Returns false if any specified name is not found.
 */
bool sepol_impl::add_type_rule(Str s, Str t, Str c, Str d, int effect) {
    type_datum_t *src, *tgt, *def;
    class_datum_t *cls;

    src = hashtab_find(db->p_types.table, s);
    if (src == nullptr) {
        LOGW("source type %.*s does not exist\n", (int) s.size(), s.data());
        return false;
    }
    tgt = hashtab_find(db->p_types.table, t);
    if (tgt == nullptr) {
        LOGW("target type %.*s does not exist\n", (int) t.size(), t.data());
        return false;
    }
    cls = hashtab_find(db->p_classes.table, c);
    if (cls == nullptr) {
        LOGW("class %.*s does not exist\n", (int) c.size(), c.data());
        return false;
    }
    def = hashtab_find(db->p_types.table, d);
    if (def == nullptr) {
        LOGW("default type %.*s does not exist\n", (int) d.size(), d.data());
        return false;
    }

    avtab_key_t key;
    key.source_type = src->s.value;
    key.target_type = tgt->s.value;
    key.target_class = cls->s.value;
    key.specified = effect;

    avtab_ptr_t node = get_avtab_node(&key, nullptr);
    // Type rules store the default type's value in the datum data field
    node->datum.data = def->s.value;

    return true;
}

/**
 * Add a filename-based type_transition rule.
 * This handles the `type_transition source target:class default_Type object_name;` syntax.
 *
 * The filename_trans hash table stores keys as (target_type, target_class, filename)
 * tuples. If a matching key exists and the source type is not already in the stypes
 * bitmap, it is added. Duplicate (same source) entries overwrite the otype.
 */
bool sepol_impl::add_filename_trans(Str s, Str t, Str c, Str d, Str o) {
    type_datum_t *src, *tgt, *def;
    class_datum_t *cls;

    src = hashtab_find(db->p_types.table, s);
    if (src == nullptr) {
        LOGW("source type %.*s does not exist\n", (int) s.size(), s.data());
        return false;
    }
    tgt = hashtab_find(db->p_types.table, t);
    if (tgt == nullptr) {
        LOGW("target type %.*s does not exist\n", (int) t.size(), t.data());
        return false;
    }
    cls = hashtab_find(db->p_classes.table, c);
    if (cls == nullptr) {
        LOGW("class %.*s does not exist\n", (int) c.size(), c.data());
        return false;
    }
    def = hashtab_find(db->p_types.table, d);
    if (def == nullptr) {
        LOGW("default type %.*s does not exist\n", (int) d.size(), d.data());
        return false;
    }

    // Build the hash key for filename_trans
    array<char, 256> key_name{};
    copy_str(key_name, o);
    filename_trans_key_t key;
    key.ttype = tgt->s.value;
    key.tclass = cls->s.value;
    key.name = key_name.data();

    // Walk the chain for this key
    filename_trans_datum_t *trans = hashtab_find(db->filename_trans, (hashtab_key_t) &key);
    filename_trans_datum_t *last = nullptr;
    while (trans) {
        if (ebitmap_get_bit(&trans->stypes, src->s.value - 1)) {
            // Source already exists: overwrite otype and return
            trans->otype = def->s.value;
            return true;
        }
        if (trans->otype == def->s.value)
            break; // Found a node with matching otype, reuse it
        last = trans;
        trans = trans->next;
    }

    if (trans == nullptr) {
        // No existing node: allocate a new one
        trans = auto_cast(calloc(sizeof(*trans), 1));
        ebitmap_init(&trans->stypes);
        trans->otype = def->s.value;
    }

    // Link into the chain
    if (last) {
        last->next = trans;
    } else {
        // First entry for this key: allocate permanent key storage and insert
        filename_trans_key_t *new_key = auto_cast(malloc(sizeof(*new_key)));
        memcpy(new_key, &key, sizeof(key));
        new_key->name = strdup(key.name);
        hashtab_insert(db->filename_trans, (hashtab_key_t) new_key, trans);
    }

    db->filename_trans_count++;
    return ebitmap_set_bit(&trans->stypes, src->s.value - 1, 1) == 0;
}

/**
 * Add a genfscon rule: maps a filesystem path to a SELinux context.
 *
 * Creates the context from a string, then finds or creates the genfs
 * filesystem node and the ocontext path node within it.
 */
bool sepol_impl::add_genfscon(Str fs_name, Str path, Str context) {
    // Create the SELinux context structure from the string representation
    context_struct_t *ctx;
    if (context_from_string(nullptr, db, &ctx, context.data(), context.size())) {
        LOGW("Failed to create context from string [%.*s]\n", (int) context.size(), context.data());
        return false;
    }

    // Find or create the genfs filesystem entry
    genfs_t *fs = list_find(db->genfs, [&](genfs_t *n) {
        return str_eq(n->fstype, fs_name);
    });
    if (fs == nullptr) {
        fs = auto_cast(calloc(sizeof(*fs), 1));
        fs->fstype = dup_str(fs_name);
        fs->next = db->genfs;  // Prepend to the genfs list
        db->genfs = fs;
    }

    // Find or create the path-based ocontext within the genfs entry
    ocontext_t *o_ctx = list_find(fs->head, [&](ocontext_t *n) {
        return str_eq(n->u.name, path);
    });
    if (o_ctx == nullptr) {
        o_ctx = auto_cast(calloc(sizeof(*o_ctx), 1));
        o_ctx->u.name = dup_str(path);
        o_ctx->next = fs->head;  // Prepend to the fs's ocontext list
        fs->head = o_ctx;
    }

    // Set the context
    memset(o_ctx->context, 0, sizeof(o_ctx->context));
    memcpy(&o_ctx->context[0], ctx, sizeof(*ctx));
    free(ctx);

    return true;
}

/**
 * Add a new type or attribute to the policy.
 *
 * Steps:
 * 1. Check if type already exists (no-op if so)
 * 2. Allocate and initialize a new type_datum_t
 * 3. Insert into the types symbol table with a new value
 * 4. Update type_attr_map and attr_type_map arrays
 * 5. Re-index the policy (decls, classes, others)
 * 6. Add the new type to all roles' type sets
 */
bool sepol_impl::add_type(Str type_name, uint32_t flavor) {
    type_datum_t *type = hashtab_find(db->p_types.table, type_name);
    if (type) {
        LOGW("Type %.*s already exists\n", (int) type_name.size(), type_name.data());
        return true;
    }

    type = auto_cast(malloc(sizeof(*type)));
    type_datum_init(type);
    type->primary = 1;
    type->flavor = flavor;

    uint32_t value = 0;
    auto ty_name = dup_str(type_name);
    if (symtab_insert(db, SYM_TYPES, ty_name, type, SCOPE_DECL, 1, &value)) {
        free(ty_name);
        return false;
    }
    type->s.value = value;
    ebitmap_set_bit(&db->global->branch_list->declared.p_types_scope, value - 1, 1);

    // Resize type_attr_map and attr_type_map to accommodate the new type
    auto new_size = sizeof(ebitmap_t) * db->p_types.nprim;
    db->type_attr_map = auto_cast(realloc(db->type_attr_map, new_size));
    db->attr_type_map = auto_cast(realloc(db->attr_type_map, new_size));
    ebitmap_init(&db->type_attr_map[value - 1]);
    ebitmap_init(&db->attr_type_map[value - 1]);
    // A type always has itself in its type_attr_map
    ebitmap_set_bit(&db->type_attr_map[value - 1], value - 1, 1);

    // Re-index various policy structures to reflect the new type
    if (policydb_index_decls(nullptr, db) ||
        policydb_index_classes(db) || policydb_index_others(nullptr, db, 0))
        return false;

    // Add the type to all roles so processes in any role can use it
    for (int i = 0; i < db->p_roles.nprim; ++i) {
        ebitmap_set_bit(&db->role_val_to_struct[i]->types.negset, value - 1, 0);
        ebitmap_set_bit(&db->role_val_to_struct[i]->types.types, value - 1, 1);
        type_set_expand(&db->role_val_to_struct[i]->types, &db->role_val_to_struct[i]->cache, db, 0);
    }

    return true;
}

/**
 * Set a type's permissive/enforcing state in the permissive_map bitmap.
 * If type_name is empty, sets the state for ALL types.
 */
bool sepol_impl::set_type_state(Str type_name, bool permissive) {
    type_datum_t *type;
    if (type_name.empty()) {
        // Set state for every type in the policy
        hashtab_for_each(db->p_types.table, [&](hashtab_ptr_t node) {
            type = auto_cast(node->datum);
            if (ebitmap_set_bit(&db->permissive_map, type->s.value, permissive))
                LOGW("Could not set bit in permissive map\n");
        });
    } else {
        type = hashtab_find(db->p_types.table, type_name);
        if (type == nullptr) {
            LOGW("type %.*s does not exist\n", (int) type_name.size(), type_name.data());
            return false;
        }
        if (ebitmap_set_bit(&db->permissive_map, type->s.value, permissive)) {
            LOGW("Could not set bit in permissive map\n");
            return false;
        }
    }
    return true;
}

/**
 * Add a type to an attribute by setting bits in both
 * type_attr_map (type -> attributes) and attr_type_map (attribute -> types).
 * Also updates constraint expressions that reference this attribute.
 */
void sepol_impl::add_typeattribute(type_datum_t *type, type_datum_t *attr) {
    // type_attr_map: for this type, set the attribute's bit
    ebitmap_set_bit(&db->type_attr_map[type->s.value - 1], attr->s.value - 1, 1);
    // attr_type_map: for this attribute, set the type's bit
    ebitmap_set_bit(&db->attr_type_map[attr->s.value - 1], type->s.value - 1, 1);

    // Update constraint expressions that use this attribute
    hashtab_for_each(db->p_classes.table, [&](hashtab_ptr_t node){
        auto cls = static_cast<class_datum_t *>(node->datum);
        list_for_each(cls->constraints, [&](constraint_node_t *n) {
            list_for_each(n->expr, [&](constraint_expr_t *e) {
                if (e->expr_type == CEXPR_NAMES &&
                    ebitmap_get_bit(&e->type_names->types, attr->s.value - 1)) {
                    // Add the type to the constraint's names bitmap
                    ebitmap_set_bit(&e->names, type->s.value - 1, 1);
                }
            });
        });
    });
}

/**
 * Add a type to an attribute by string names.
 * Validates that both type and attribute exist and have the correct flavors.
 */
bool sepol_impl::add_typeattribute(Str type, Str attr) {
    type_datum_t *type_d = hashtab_find(db->p_types.table, type);
    if (type_d == nullptr) {
        LOGW("type %.*s does not exist\n", (int) type.size(), type.data());
        return false;
    } else if (type_d->flavor == TYPE_ATTRIB) {
        LOGW("type %.*s is an attribute\n", (int) type.size(), type.data());
        return false;
    }

    type_datum *attr_d = hashtab_find(db->p_types.table, attr);
    if (attr_d == nullptr) {
        LOGW("attribute %.*s does not exist\n", (int) attr.size(), attr.data());
        return false;
    } else if (attr_d->flavor != TYPE_ATTRIB) {
        LOGW("type %.*s is not an attribute \n", (int) attr.size(), attr.data());
        return false;
    }

    add_typeattribute(type_d, attr_d);
    return true;
}

/**
 * Remove all dontaudit rules from the policy.
 * Iterates over the entire avtab and removes any node whose specified
 * field is AVTAB_AUDITDENY or AVTAB_XPERMS_DONTAUDIT.
 */
void SePolicy::strip_dontaudit() noexcept {
    avtab_for_each(&impl->db->te_avtab, [this](avtab_ptr_t node) {
        if (node->key.specified == AVTAB_AUDITDENY || node->key.specified == AVTAB_XPERMS_DONTAUDIT)
            avtab_remove_node(&impl->db->te_avtab, node);
    });
}

/**
 * Print all policy rules to stdout in a human-readable format.
 * Output order: attributes, types, AV rules (allow/auditallow/dontaudit),
 * type rules (type_transition/type_change/type_member), xperm rules,
 * filename_trans rules, and genfscon rules.
 */
void SePolicy::print_rules() const noexcept {
    // Print attributes
    hashtab_for_each(impl->db->p_types.table, [this](hashtab_ptr_t node) {
        type_datum_t *type = auto_cast(node->datum);
        if (type->flavor == TYPE_ATTRIB) {
            impl->print_type(stdout, type);
        }
    });
    // Print types with their attributes
    hashtab_for_each(impl->db->p_types.table, [this](hashtab_ptr_t node) {
        type_datum_t *type = auto_cast(node->datum);
        if (type->flavor == TYPE_TYPE) {
            impl->print_type(stdout, type);
        }
    });
    // Print access vector rules (allow, auditallow, dontaudit, type_transition, xperm)
    avtab_for_each(&impl->db->te_avtab, [this](avtab_ptr_t node) {
        impl->print_avtab(stdout, node);
    });
    // Print filename_trans rules
    hashtab_for_each(impl->db->filename_trans, [this](hashtab_ptr_t node) {
        impl->print_filename_trans(stdout, node);
    });
    // Print genfscon rules
    list_for_each(impl->db->genfs, [this](genfs_t *genfs) {
        list_for_each(genfs->head, [&](ocontext *context) {
            char *ctx = nullptr;
            size_t len = 0;
            if (context_to_string(nullptr, impl->db, &context->context[0], &ctx, &len) == 0) {
                fprintf(stdout, "genfscon %s %s %s\n", genfs->fstype, context->u.name, ctx);
                free(ctx);
            }
        });
    });
}

/**
 * Print a type or attribute declaration.
 * For attributes: prints "attribute <name>"
 * For types: prints "type <name> { <attr1> <attr2> ... }"
 * Also prints "permissive <name>" if the type is in the permissive map.
 */
void sepol_impl::print_type(FILE *fp, type_datum_t *type) {
    const char *name = db->p_type_val_to_name[type->s.value - 1];
    if (name == nullptr)
        return;

    if (type->flavor == TYPE_ATTRIB) {
        fprintf(fp, "attribute %s\n", name);
    } else if (type->flavor == TYPE_TYPE) {
        bool first = true;
        ebitmap_t *bitmap = &db->type_attr_map[type->s.value - 1];
        // Iterate over all attributes this type belongs to
        for (uint32_t i = 0; i <= bitmap->highbit; ++i) {
            if (ebitmap_get_bit(bitmap, i)) {
                auto attr_type = db->type_val_to_struct[i];
                if (attr_type->flavor == TYPE_ATTRIB) {
                    if (const char *attr = db->p_type_val_to_name[i]) {
                        if (first) {
                            fprintf(fp, "type %s {", name);
                            first = false;
                        }
                        fprintf(fp, " %s", attr);
                    }
                }
            }
        }
        if (!first) {
            fprintf(fp, " }\n");
        }
    }

    // Print permissive state
    if (ebitmap_get_bit(&db->permissive_map, type->s.value)) {
        fprintf(fp, "permissive %s\n", name);
    }
}

/**
 * Print an avtab entry as a human-readable SELinux policy rule.
 * Handles:
 * - AV rules (allow, auditallow, dontaudit) with permission list
 * - Type rules (type_transition, type_change, type_member) with default type
 * - Xperm rules (allowxperm, auditallowxperm, dontauditxperm) with ioctl ranges
 *
 * Permission names are looked up from the class's permission table and cached
 * in class_perm_names for efficiency.
 */
void sepol_impl::print_avtab(FILE *fp, avtab_ptr_t node) {
    const char *src = db->p_type_val_to_name[node->key.source_type - 1];
    const char *tgt = db->p_type_val_to_name[node->key.target_type - 1];
    const char *cls = db->p_class_val_to_name[node->key.target_class - 1];
    if (src == nullptr || tgt == nullptr || cls == nullptr)
        return;

    if (node->key.specified & AVTAB_AV) {
        // === Access Vector Rules (allow/auditallow/dontaudit) ===
        uint32_t data = node->datum.data;
        const char *name;
        switch (node->key.specified) {
            case AVTAB_ALLOWED:
                name = "allow";
                break;
            case AVTAB_AUDITALLOW:
                name = "auditallow";
                break;
            case AVTAB_AUDITDENY:
                name = "dontaudit";
                // Invert the data bits for dontaudit (stored inverted internally)
                data = ~data;
                break;
            default:
                return;
        }

        class_datum_t *clz = db->class_val_to_struct[node->key.target_class - 1];
        if (clz == nullptr)
            return;

        // Lazily build permission name cache for this class
        auto it = class_perm_names.find(cls);
        if (it == class_perm_names.end()) {
            it = class_perm_names.try_emplace(cls).first;
            hashtab_for_each(clz->permissions.table, [&](hashtab_ptr_t node) {
                perm_datum_t *perm = auto_cast(node->datum);
                it->second[perm->s.value - 1] = node->key;
            });
            // Also check common permissions (inherited from common class)
            if (clz->comdatum) {
                hashtab_for_each(clz->comdatum->permissions.table, [&](hashtab_ptr_t node) {
                    perm_datum_t *perm = auto_cast(node->datum);
                    it->second[perm->s.value - 1] = node->key;
                });
            }
        }

        // Print permission list (set bits in the data mask)
        bool first = true;
        for (int i = 0; i < 32; ++i) {
            if (data & (1u << i)) {
                if (const char *perm = it->second[i]) {
                    if (first) {
                        fprintf(fp, "%s %s %s %s {", name, src, tgt, cls);
                        first = false;
                    }
                    fprintf(fp, " %s", perm);
                }
            }
        }
        if (!first) {
            fprintf(fp, " }\n");
        }
    } else if (node->key.specified & AVTAB_TYPE) {
        // === Type Rules (type_transition/type_change/type_member) ===
        const char *name;
        switch (node->key.specified) {
            case AVTAB_TRANSITION:
                name = "type_transition";
                break;
            case AVTAB_MEMBER:
                name = "type_member";
                break;
            case AVTAB_CHANGE:
                name = "type_change";
                break;
            default:
                return;
        }
        if (const char *def = db->p_type_val_to_name[node->datum.data - 1]) {
            fprintf(fp, "%s %s %s %s %s\n", name, src, tgt, cls, def);
        }
    } else if (node->key.specified & AVTAB_XPERMS) {
        // === Extended Permissions Rules (allowxperm/auditallowxperm/dontauditxperm) ===
        const char *name;
        switch (node->key.specified) {
            case AVTAB_XPERMS_ALLOWED:
                name = "allowxperm";
                break;
            case AVTAB_XPERMS_AUDITALLOW:
                name = "auditallowxperm";
                break;
            case AVTAB_XPERMS_DONTAUDIT:
                name = "dontauditxperm";
                break;
            default:
                return;
        }
        avtab_extended_perms_t *xperms = node->datum.xperms;
        if (xperms == nullptr)
            return;

        // Collapse the 256-bit perms bitmap into contiguous ranges
        vector<pair<uint8_t, uint8_t>> ranges;
        {
            int low = -1;
            for (int i = 0; i < 256; ++i) {
                if (xperm_test(i, xperms->perms)) {
                    if (low < 0) {
                        low = i;
                    }
                    if (i == 255) {
                        ranges.emplace_back(low, 255);
                    }
                } else if (low >= 0) {
                    ranges.emplace_back(low, i - 1);
                    low = -1;
                }
            }
        }

        // Convert an 8-bit position to a 16-bit ioctl value
        auto to_value = [&](uint8_t val) -> uint16_t {
            if (xperms->specified == AVTAB_XPERMS_IOCTLFUNCTION) {
                // Function-level: combine driver and function
                return (((uint16_t) xperms->driver) << 8) | val;
            } else {
                // Driver-level: only the driver number
                return ((uint16_t) val) << 8;
            }
        };

        if (!ranges.empty()) {
            fprintf(fp, "%s %s %s %s ioctl {", name, src, tgt, cls);
            for (auto [l, h] : ranges) {
                uint16_t low = to_value(l);
                uint16_t high = to_value(h);
                if (low == high) {
                    fprintf(fp, " 0x%04X", low);
                } else {
                    fprintf(fp, " 0x%04X-0x%04X", low, high);
                }
            }
            fprintf(fp, " }\n");
        }
    }
}

/**
 * Print a filename_trans entry as a type_transition rule with object name.
 * Iterates over all source types in the stypes bitmap and prints
 * a rule for each.
 */
void sepol_impl::print_filename_trans(FILE *fp, hashtab_ptr_t node) {
    auto key = reinterpret_cast<filename_trans_key_t *>(node->key);
    filename_trans_datum_t *trans = auto_cast(node->datum);

    const char *tgt = db->p_type_val_to_name[key->ttype - 1];
    const char *cls = db->p_class_val_to_name[key->tclass - 1];
    const char *def = db->p_type_val_to_name[trans->otype - 1];
    if (tgt == nullptr || cls == nullptr || def == nullptr || key->name == nullptr)
        return;

    for (uint32_t i = 0; i <= trans->stypes.highbit; ++i) {
        if (ebitmap_get_bit(&trans->stypes, i)) {
            if (const char *src = db->p_type_val_to_name[i]) {
                fprintf(fp, "type_transition %s %s %s %s %s\n", src, tgt, cls, def, key->name);
            }
        }
    }
}
