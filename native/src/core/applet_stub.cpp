/**
 * Stub entry point used when magisk is executed as an applet
 * via a pre-defined APPLET_STUB_MAIN macro (e.g., for magiskpolicy).
 */
#include <core.hpp>

/** Stub entry point that delegates to APPLET_STUB_MAIN (e.g. magiskpolicy). */
int main(int argc, char *argv[]) {
    if (argc < 1)
        return 1;
    cmdline_logging();
    init_argv0(argc, argv);
    umask(0);
    return APPLET_STUB_MAIN(argc, argv);
}
