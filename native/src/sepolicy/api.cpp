/**
 * High-level SELinux policy manipulation API.
 * Implements the public SePolicy methods (allow, deny, permissive, type, etc.)
 * that it expand wildcard vectors and delegate to the sepol_impl backend.
 *
 * The `expand` template machinery recursively iterates over all combinations
 * of vector arguments, calling the underlying sepol_impl method for each
 * concrete (source, target, class, permission) tuple. Empty vectors act
 * as wildcards, expanding to all possible values in the policy.
 */
#include <base.hpp>

#include "include/sepolicy.hpp"

using Str = rust::Str;
using StrVec = rust::Vec<rust::Str>;
using Xperms = rust::Vec<Xperm>;

#if 0
template<typename Arg>
std::string as_str(const Arg &arg) {
    if constexpr (std::is_same_v<Arg, Xperm>) {
        return (std::string) SePolicy::xperm_to_string(arg);
    } else if constexpr (std::is_same_v<Arg, rust::Str>) {
        return arg.empty() ? "*" : (std::string) arg;
    }
}

/** Debug helper: print all rules passing through the public API */
template<typename ...Args>
static void print_rule(const char *action, Args ...args) {
    std::string s;
    s = (... + (" " + as_str(args)));
    LOGD("%s%s\n", action, s.data());
}
#else
#define print_rule(...) ((void) 0)
#endif

/** Base case: invoke the callable with accumulated arguments */
template<typename F, typename ...T>
requires(std::invocable<F, T...>)
static inline void expand(F &&f, T &&...args) {
    f(std::forward<T>(args)...);
}

/** Single string argument: pass through as-is */
template<typename ...T>
static inline void expand(Str s, T &&...args) {
    expand(std::forward<T>(args)..., s);
}

/**
 * Vector of strings: if empty, pass a single empty Str (wildcard);
 * otherwise, recurse for each element.
 */
template<typename ...T>
static inline void expand(const StrVec &vec, T &&...args) {
    if (vec.empty()) {
        expand(std::forward<T>(args)..., rust::Str{});
    } else {
        for (auto s : vec) {
            expand(std::forward<T>(args)..., s);
        }
    }
}

/** Vector of Xperm values: recurse for each element */
template<typename ...T>
static inline void expand(const Xperms &vec, T &&...args) {
    for (auto &p : vec) {
        expand(std::forward<T>(args)..., p);
    }
}

/** Add allow rules (AVTAB_ALLOWED, non-inverted) */
void SePolicy::allow(StrVec src, StrVec tgt, StrVec cls, StrVec perm) noexcept {
    expand(src, tgt, cls, perm, [this](auto ...args) {
        print_rule("allow", args...);
        impl->add_rule(args..., AVTAB_ALLOWED, false);
    });
}

/** Add deny rules by inverting allow bits (AVTAB_ALLOWED, inverted) */
void SePolicy::deny(StrVec src, StrVec tgt, StrVec cls, StrVec perm) noexcept {
    expand(src, tgt, cls, perm, [this](auto ...args) {
        print_rule("deny", args...);
        impl->add_rule(args..., AVTAB_ALLOWED, true);
    });
}

/** Add auditallow rules (allow + audit logging) */
void SePolicy::auditallow(StrVec src, StrVec tgt, StrVec cls, StrVec perm) noexcept {
    expand(src, tgt, cls, perm, [this](auto ...args) {
        print_rule("auditallow", args...);
        impl->add_rule(args..., AVTAB_AUDITALLOW, false);
    });
}

/** Add dontaudit rules (suppress audit logging for denied perms) */
void SePolicy::dontaudit(StrVec src, StrVec tgt, StrVec cls, StrVec perm) noexcept {
    expand(src, tgt, cls, perm, [this](auto ...args) {
        print_rule("dontaudit", args...);
        impl->add_rule(args..., AVTAB_AUDITDENY, true);
    });
}

/** Set domains to permissive mode (in the permissive_map) */
void SePolicy::permissive(StrVec types) noexcept {
    expand(types, [this](auto ...args) {
        print_rule("permissive", args...);
        impl->set_type_state(args..., true);
    });
}

/** Set domains to enforcing mode */
void SePolicy::enforce(StrVec types) noexcept {
    expand(types, [this](auto ...args) {
        print_rule("enforce", args...);
        impl->set_type_state(args..., false);
    });
}

/** Assign attributes to one or more types */
void SePolicy::typeattribute(StrVec types, StrVec attrs) noexcept {
    expand(types, attrs, [this](auto ...args) {
        print_rule("typeattribute", args...);
        impl->add_typeattribute(args...);
    });
}

/** Declare a new type and optionally assign it to attributes */
void SePolicy::type(Str type, StrVec attrs) noexcept {
    expand(type, attrs, [this](auto name, auto attr) {
        print_rule("type", name, attr);
        impl->add_type(name, TYPE_TYPE) && impl->add_typeattribute(name, attr);
    });
}

/** Declare a new attribute type */
void SePolicy::attribute(Str name) noexcept {
    expand(name, [this](auto ...args) {
        print_rule("attribute", args...);
        impl->add_type(args..., TYPE_ATTRIB);
    });
}

/**
 * Add type_transition rules.
 * If an object name (filename) is provided, adds a filename-based type_transition;
 * otherwise adds a regular type_transition rule.
 */
void SePolicy::type_transition(Str src, Str tgt, Str cls, Str def, Str obj) noexcept {
    expand(src, tgt, cls, def, obj, [this](auto s, auto t, auto c, auto d, auto o) {
        if (!o.empty()) {
            print_rule("type_transition", s, t, c, d, o);
            impl->add_filename_trans(s, t, c, d, o);
        } else {
            print_rule("type_transition", s, t, c, d);
            impl->add_type_rule(s, t, c, d, AVTAB_TRANSITION);
        }
    });
}

/** Add type_change rules (relabel-to on re-label operations) */
void SePolicy::type_change(Str src, Str tgt, Str cls, Str def) noexcept {
    expand(src, tgt, cls, def, [this](auto ...args) {
        print_rule("type_change", args...);
        impl->add_type_rule(args..., AVTAB_CHANGE);
    });
}

/** Add type_member rules (polyinstantiated directory membership) */
void SePolicy::type_member(Str src, Str tgt, Str cls, Str def) noexcept {
    expand(src, tgt, cls, def, [this](auto ...args) {
        print_rule("type_member", args...);
        impl->add_type_rule(args..., AVTAB_MEMBER);
    });
}

/** Add genfscon rules (filesystem label mapping for pseudo/GPFS FS) */
void SePolicy::genfscon(Str fs_name, Str path, Str ctx) noexcept {
    expand(fs_name, path, ctx, [this](auto ...args) {
        print_rule("genfscon", args...);
        impl->add_genfscon(args...);
    });
}

/** Add allowxperm rules (extended ioctl permission ranges) */
void SePolicy::allowxperm(StrVec src, StrVec tgt, StrVec cls, Xperms xperm) noexcept {
    expand(src, tgt, cls, xperm, [this](auto ...args) {
        print_rule("allowxperm", args...);
        impl->add_xperm_rule(args..., AVTAB_XPERMS_ALLOWED);
    });
}

/** Add auditallowxperm rules */
void SePolicy::auditallowxperm(StrVec src, StrVec tgt, StrVec cls, Xperms xperm) noexcept {
    expand(src, tgt, cls, xperm, [this](auto ...args) {
        print_rule("auditallowxperm", args...);
        impl->add_xperm_rule(args..., AVTAB_XPERMS_AUDITALLOW);
    });
}

/** Add dontauditxperm rules */
void SePolicy::dontauditxperm(StrVec src, StrVec tgt, StrVec cls, Xperms xperm) noexcept {
    expand(src, tgt, cls, xperm, [this](auto ...args) {
        print_rule("dontauditxperm", args...);
        impl->add_xperm_rule(args..., AVTAB_XPERMS_DONTAUDIT);
    });
}
