#!/usr/bin/env python3
"""
Generates jni_hooks.hpp — a C++ header containing JNINativeMethod arrays for
all known Zygote#nativeForkAndSpecialize / nativeSpecializeAppProcess /
nativeForkSystemServer variants across Android API levels and OEM forks.

Each method entry is an inline lambda that:
  1. Constructs a typed args struct (AppSpecializeArgs / ServerSpecializeArgs)
  2. Calls the Zygisk pre-hook
  3. Forwards to the original JNI function via the stored fnPtr
  4. Calls the Zygisk post-hook
  5. Returns the original return value

Supported variants:
  - AOSP:   l, o, p, q_alt, r, u, b
  - Samsung: samsung_m, samsung_n, samsung_o, samsung_p, samsung_q
  - Nubia:   nubia_u
  - XR:      xr_u
"""

primitives = ["jint", "jboolean", "jlong"]


class JType:
    """Represents a JNI type with its C++ and JNI signature forms."""
    def __init__(self, cpp: str, jni: str):
        self.cpp = cpp
        self.jni = jni


class JArray(JType):
    """Represents a JNI array type (e.g. jintArray, jobjectArray)."""
    def __init__(self, type: JType):
        if type.cpp in primitives:
            name = type.cpp + "Array"
        else:
            name = "jobjectArray"
        super().__init__(name, "[" + type.jni)


class Argument:
    """Describes a JNI method argument with name, type, and whether it must be set via set_arg."""
    def __init__(self, name: str, type: JType, set_arg=False):
        self.name = name
        self.type = type
        self.set_arg = set_arg

    def cpp(self) -> str:
        return f"{self.type.cpp} {self.name}"


# Args we don't care about — auto-generated name
class Anon(Argument):
    """An argument whose name is auto-generated (for OEM-specific params we ignore)."""
    cnt = 0

    def __init__(self, type: JType):
        super().__init__(f"_{Anon.cnt}", type)
        Anon.cnt += 1


class Return:
    """Describes a JNI method return type and an optional C++ expression for the return value."""
    def __init__(self, value: str, type: JType):
        self.value = value
        self.type = type


class JNIMethod:
    """Describes a JNI method: name, return type, and argument list."""
    def __init__(self, name: str, ret: Return, args: list[Argument]):
        self.name = name
        self.ret = ret
        self.args = args

    def arg_list_name(self) -> str:
        """Returns the argument list as comma-separated names (env, clazz, ...)."""
        return "env, clazz, " + ", ".join(map(lambda x: x.name, self.args))

    def arg_list_cpp(self) -> str:
        """Returns the argument list as C++ declarations (JNIEnv *env, jclass clazz, ...)."""
        return "JNIEnv *env, jclass clazz, " + ", ".join(
            map(lambda x: x.cpp(), self.args)
        )

    def cpp_fn_type(self) -> str:
        """Returns a C++ function-pointer type for the JNI method."""
        return f"{self.ret.type.cpp}(*)({self.arg_list_cpp()}"

    def cpp_lambda_sig(self) -> str:
        """Returns the lambda signature with [[clang::no_stack_protector]] attribute."""
        return f"[] [[clang::no_stack_protector]] ({self.arg_list_cpp()}) static -> {self.ret.type.cpp}"

    def jni_sig(self):
        """Returns the JNI signature string (e.g. (II)V)."""
        args = "".join(map(lambda x: x.type.jni, self.args))
        return f"({args}){self.ret.type.jni}"


class JNIHook(JNIMethod):
    """A JNI hook method that wraps an original JNI function with Zygisk pre/post callbacks."""
    def __init__(self, ver: str, ret: Return, args: list[Argument]):
        name = f"{self.hook_target()}_{ver}"
        super().__init__(name, ret, args)

    def hook_target(self):
        """Returns the name of the JNI function being hooked (overridden by subclasses)."""
        return ""

    def body(self, orig_fn_ptr: str):
        """Returns the lambda body that invokes pre/post hooks around the original call."""
        return ""


def ind(i):
    """Returns a newline + i*4 spaces for indentation."""
    return "\n" + "    " * i


# Common JNI types used in Zygote methods
jint = JType("jint", "I")
jintArray = JArray(jint)
jstring = JType("jstring", "Ljava/lang/String;")
jboolean = JType("jboolean", "Z")
jlong = JType("jlong", "J")
void = JType("void", "V")


class ForkApp(JNIHook):
    """Hook for nativeForkAndSpecialize (returns pid)."""
    def __init__(self, ver, args):
        super().__init__(ver, Return("ctx.pid", jint), args)

    def hook_target(self):
        return "nativeForkAndSpecialize"

    def init_args(self):
        return "AppSpecializeArgs_v5 args(uid, gid, gids, runtime_flags, rlimits, mount_external, se_info, nice_name, instruction_set, app_data_dir);"

    def body(self, orig_fn_ptr: str):
        decl = ""
        decl += ind(3) + self.init_args()
        for a in self.args:
            if a.set_arg:
                decl += ind(3) + f"args.{a.name} = &{a.name};"
        decl += ind(3) + "ZygiskContext ctx(env, &args);"
        decl += ind(3) + f"ctx.{self.hook_target()}_pre();"
        decl += ind(3) + f"reinterpret_cast<{self.cpp_fn_type()})>({orig_fn_ptr})("
        decl += ind(4) + self.arg_list_name()
        decl += ind(3) + ");"
        decl += ind(3) + f"ctx.{self.hook_target()}_post();"
        if self.ret.value:
            decl += ind(3) + f"return {self.ret.value};"
        return decl


class SpecializeApp(ForkApp):
    """Hook for nativeSpecializeAppProcess (returns void)."""
    def __init__(self, ver: str, args: list[Argument]):
        super().__init__(ver, args)
        self.ret = Return("", void)

    def hook_target(self):
        return "nativeSpecializeAppProcess"


class ForkServer(ForkApp):
    """Hook for nativeForkSystemServer (returns pid, uses ServerSpecializeArgs)."""
    def hook_target(self):
        return "nativeForkSystemServer"

    def init_args(self):
        return "ServerSpecializeArgs_v1 args(uid, gid, gids, runtime_flags, permitted_capabilities, effective_capabilities);"


# Common named arguments for Zygote fork/specialize methods
uid = Argument("uid", jint)
gid = Argument("gid", jint)
gids = Argument("gids", jintArray)
runtime_flags = Argument("runtime_flags", jint)
rlimits = Argument("rlimits", JArray(jintArray))
mount_external = Argument("mount_external", jint)
se_info = Argument("se_info", jstring)
nice_name = Argument("nice_name", jstring)
fds_to_close = Argument("fds_to_close", jintArray)
instruction_set = Argument("instruction_set", jstring)
app_data_dir = Argument("app_data_dir", jstring)

# API o: fds_to_ignore
fds_to_ignore = Argument("fds_to_ignore", jintArray, True)

# API p: is_child_zygote
is_child_zygote = Argument("is_child_zygote", jboolean, True)

# API q_alt: is_top_app
is_top_app = Argument("is_top_app", jboolean, True)

# API q on XR: is_perception_app (not forwarded to args)
is_perception_app = Argument("is_perception_app", jboolean, False)

# API r: package data / whitelisted data / mount dirs
pkg_data_info_list = Argument("pkg_data_info_list", JArray(jstring), True)
whitelisted_data_info_list = Argument(
    "whitelisted_data_info_list", JArray(jstring), True
)
mount_data_dirs = Argument("mount_data_dirs", jboolean, True)
mount_storage_dirs = Argument("mount_storage_dirs", jboolean, True)

# API u (QPR2): mount_sysprop_overrides
mount_sysprop_overrides = Argument("mount_sysprop_overrides", jboolean, True)

# API b (QPR2): use_fifo_ui (not forwarded to args)
use_fifo_ui = Argument("use_fifo_ui", jboolean, False)

# Server-specific: capabilities
permitted_capabilities = Argument("permitted_capabilities", jlong)
effective_capabilities = Argument("effective_capabilities", jlong)

# JNI method definitions — one per known API variant.
# Each captures the exact parameter list for that API level / OEM fork.
fas_l = ForkApp(
    "l",
    [
        uid,
        gid,
        gids,
        runtime_flags,
        rlimits,
        mount_external,
        se_info,
        nice_name,
        fds_to_close,
        instruction_set,
        app_data_dir,
    ],
)

fas_o = ForkApp(
    "o",
    [
        uid,
        gid,
        gids,
        runtime_flags,
        rlimits,
        mount_external,
        se_info,
        nice_name,
        fds_to_close,
        fds_to_ignore,
        instruction_set,
        app_data_dir,
    ],
)

fas_p = ForkApp(
    "p",
    [
        uid,
        gid,
        gids,
        runtime_flags,
        rlimits,
        mount_external,
        se_info,
        nice_name,
        fds_to_close,
        fds_to_ignore,
        is_child_zygote,
        instruction_set,
        app_data_dir,
    ],
)

fas_q_alt = ForkApp(
    "q_alt",
    [
        uid,
        gid,
        gids,
        runtime_flags,
        rlimits,
        mount_external,
        se_info,
        nice_name,
        fds_to_close,
        fds_to_ignore,
        is_child_zygote,
        instruction_set,
        app_data_dir,
        is_top_app,
    ],
)

fas_r = ForkApp(
    "r",
    [
        uid,
        gid,
        gids,
        runtime_flags,
        rlimits,
        mount_external,
        se_info,
        nice_name,
        fds_to_close,
        fds_to_ignore,
        is_child_zygote,
        instruction_set,
        app_data_dir,
        is_top_app,
        pkg_data_info_list,
        whitelisted_data_info_list,
        mount_data_dirs,
        mount_storage_dirs,
    ],
)

fas_u = ForkApp(
    "u",
    [
        uid,
        gid,
        gids,
        runtime_flags,
        rlimits,
        mount_external,
        se_info,
        nice_name,
        fds_to_close,
        fds_to_ignore,
        is_child_zygote,
        instruction_set,
        app_data_dir,
        is_top_app,
        pkg_data_info_list,
        whitelisted_data_info_list,
        mount_data_dirs,
        mount_storage_dirs,
        mount_sysprop_overrides,
    ],
)

fas_b = ForkApp(
    "b",
    [
        uid,
        gid,
        gids,
        runtime_flags,
        rlimits,
        mount_external,
        se_info,
        nice_name,
        fds_to_close,
        fds_to_ignore,
        is_child_zygote,
        instruction_set,
        app_data_dir,
        is_top_app,
        use_fifo_ui,
        pkg_data_info_list,
        whitelisted_data_info_list,
        mount_data_dirs,
        mount_storage_dirs,
        mount_sysprop_overrides,
    ],
)

fas_samsung_m = ForkApp(
    "samsung_m",
    [
        uid,
        gid,
        gids,
        runtime_flags,
        rlimits,
        mount_external,
        se_info,
        Anon(jint),
        Anon(jint),
        nice_name,
        fds_to_close,
        instruction_set,
        app_data_dir,
    ],
)

fas_samsung_n = ForkApp(
    "samsung_n",
    [
        uid,
        gid,
        gids,
        runtime_flags,
        rlimits,
        mount_external,
        se_info,
        Anon(jint),
        Anon(jint),
        nice_name,
        fds_to_close,
        instruction_set,
        app_data_dir,
        Anon(jint),
    ],
)

fas_samsung_o = ForkApp(
    "samsung_o",
    [
        uid,
        gid,
        gids,
        runtime_flags,
        rlimits,
        mount_external,
        se_info,
        Anon(jint),
        Anon(jint),
        nice_name,
        fds_to_close,
        fds_to_ignore,
        instruction_set,
        app_data_dir,
    ],
)

fas_samsung_p = ForkApp(
    "samsung_p",
    [
        uid,
        gid,
        gids,
        runtime_flags,
        rlimits,
        mount_external,
        se_info,
        Anon(jint),
        Anon(jint),
        nice_name,
        fds_to_close,
        fds_to_ignore,
        is_child_zygote,
        instruction_set,
        app_data_dir,
    ],
)

fas_nubia_u = ForkApp(
    "nubia_u",
    [
        uid,
        gid,
        gids,
        runtime_flags,
        rlimits,
        mount_external,
        se_info,
        nice_name,
        fds_to_close,
        fds_to_ignore,
        is_child_zygote,
        instruction_set,
        app_data_dir,
        is_top_app,
        use_fifo_ui,
        pkg_data_info_list,
        whitelisted_data_info_list,
        mount_data_dirs,
        mount_storage_dirs,
        mount_sysprop_overrides,
        Anon(jstring),
    ],
)

spec_q = SpecializeApp(
    "q",
    [
        uid,
        gid,
        gids,
        runtime_flags,
        rlimits,
        mount_external,
        se_info,
        nice_name,
        is_child_zygote,
        instruction_set,
        app_data_dir,
    ],
)

spec_q_alt = SpecializeApp(
    "q_alt",
    [
        uid,
        gid,
        gids,
        runtime_flags,
        rlimits,
        mount_external,
        se_info,
        nice_name,
        is_child_zygote,
        instruction_set,
        app_data_dir,
        is_top_app,
    ],
)

spec_r = SpecializeApp(
    "r",
    [
        uid,
        gid,
        gids,
        runtime_flags,
        rlimits,
        mount_external,
        se_info,
        nice_name,
        is_child_zygote,
        instruction_set,
        app_data_dir,
        is_top_app,
        pkg_data_info_list,
        whitelisted_data_info_list,
        mount_data_dirs,
        mount_storage_dirs,
    ],
)

spec_u = SpecializeApp(
    "u",
    [
        uid,
        gid,
        gids,
        runtime_flags,
        rlimits,
        mount_external,
        se_info,
        nice_name,
        is_child_zygote,
        instruction_set,
        app_data_dir,
        is_top_app,
        pkg_data_info_list,
        whitelisted_data_info_list,
        mount_data_dirs,
        mount_storage_dirs,
        mount_sysprop_overrides,
    ],
)

spec_xr_u = SpecializeApp(
    "xr_u",
    [
        uid,
        gid,
        gids,
        runtime_flags,
        rlimits,
        mount_external,
        se_info,
        nice_name,
        is_child_zygote,
        instruction_set,
        app_data_dir,
        is_top_app,
        is_perception_app,
        pkg_data_info_list,
        whitelisted_data_info_list,
        mount_data_dirs,
        mount_storage_dirs,
    ],
)

spec_samsung_q = SpecializeApp(
    "samsung_q",
    [
        uid,
        gid,
        gids,
        runtime_flags,
        rlimits,
        mount_external,
        se_info,
        Anon(jint),
        Anon(jint),
        nice_name,
        is_child_zygote,
        instruction_set,
        app_data_dir,
    ],
)

spec_nubia_u = SpecializeApp(
    "nubia_u",
    [
        uid,
        gid,
        gids,
        runtime_flags,
        rlimits,
        mount_external,
        se_info,
        nice_name,
        is_child_zygote,
        instruction_set,
        app_data_dir,
        is_top_app,
        pkg_data_info_list,
        whitelisted_data_info_list,
        mount_data_dirs,
        mount_storage_dirs,
        mount_sysprop_overrides,
        Anon(jstring),
    ],
)

server_l = ForkServer(
    "l",
    [
        uid,
        gid,
        gids,
        runtime_flags,
        rlimits,
        permitted_capabilities,
        effective_capabilities,
    ],
)

server_samsung_q = ForkServer(
    "samsung_q",
    [
        uid,
        gid,
        gids,
        runtime_flags,
        Anon(jint),
        Anon(jint),
        rlimits,
        permitted_capabilities,
        effective_capabilities,
    ],
)


def gen_jni_def(field: str, methods: list[JNIHook]):
    """Generates a C++ std::array<JNINativeMethod> initializer for the given hooks."""
    decl = ""
    decl += ind(0) + f"std::array<JNINativeMethod, {len(methods)}> {field} = {{{{"
    for i, m in enumerate(methods):
        decl += ind(1) + f"// {m.name}"
        decl += ind(1) + "{"
        decl += ind(2) + f'"{m.hook_target()}",'
        decl += ind(2) + f'"{m.jni_sig()}",'
        decl += ind(2) + f"(void *) +{m.cpp_lambda_sig()} {{"
        orig_fn_ptr = f"get_defs()->{field}[{i}].fnPtr"
        decl += m.body(orig_fn_ptr)
        decl += ind(2) + "}"
        decl += ind(1) + "},"
    decl += ind(0) + "}};"
    decl += ind(0)

    return decl


with open("jni_hooks.hpp", "w") as f:
    f.write("// Generated by gen_jni_hooks.py\n")
    f.write("#pragma once\n\n")
    f.write("struct JniHookDefinitions;\n")
    f.write("static JniHookDefinitions *get_defs();\n\n")
    f.write("struct JniHookDefinitions {\n")
    f.write(
        gen_jni_def(
            "fork_app_methods",
            [
                fas_l,
                fas_o,
                fas_p,
                fas_q_alt,
                fas_r,
                fas_u,
                fas_b,
                fas_samsung_m,
                fas_samsung_n,
                fas_samsung_o,
                fas_samsung_p,
                fas_nubia_u,
            ],
        )
    )

    f.write(
        gen_jni_def(
            "specialize_app_methods",
            [spec_q, spec_q_alt, spec_r, spec_u, spec_xr_u, spec_samsung_q, spec_nubia_u],
        )
    )

    f.write(gen_jni_def("fork_server_methods", [server_l, server_samsung_q]))

    f.write("\n};\n")
