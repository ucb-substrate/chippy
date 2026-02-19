#include <stdio.h>
#include <riscv-pk/encoding.h>
#include <stdint.h>

int main(int argc, char* argv[]) {
  printf("Hello world %s\n", argc > 1 ? argv[1] : "unknown");
  return 0;
}

