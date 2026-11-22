// Executável estático bionic arm64 sob qemu: o segmento TLS precisa de alinhamento >= 64.
extern "C" { __attribute__((used, aligned(64))) thread_local char girassol_sb_tls_align_pad[64] = {1}; }
