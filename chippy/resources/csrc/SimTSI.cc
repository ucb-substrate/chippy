#include <vpi_user.h>
#include <svdpi.h>
#include <map>
#include <string>
#include "testchip_tsi.h"

testchip_tsi_t* tsi = NULL;

extern "C" void tsi_init(svOpenArrayHandle argv, bool can_have_loadmem)
{
    int left = svLeft(argv,1);
    int right = svRight(argv,1);
    char** argv_actual = (char **) svGetArrElemPtr1(argv, 0);;

    // TODO: We should somehow inspect whether or not our backing memory supports loadmem, instead of unconditionally setting it to true
    tsi = new testchip_tsi_t(right - left + 1, argv, can_have_loadmem);
}

extern "C" int tsi_tick(
                        unsigned char out_valid,
                        unsigned char *out_ready,
                        int out_bits,

                        unsigned char *in_valid,
                        unsigned char in_ready,
                        int *in_bits)
{
    bool out_fire = *out_ready && out_valid;
    bool in_fire = *in_valid && in_ready;
    bool in_free = !(*in_valid);

    if (tsi == NULL) {
        // TODO: We should somehow inspect whether or not our backing memory supports loadmem, instead of unconditionally setting it to true
        tsi = new testchip_tsi_t(argc, argv, can_have_loadmem);
    }

    testchip_tsi_t* tsi = tsis[chip_id];
    tsi->tick(out_valid, out_bits, in_ready);
    tsi->switch_to_host();

    *in_valid = tsi->in_valid();
    *in_bits = tsi->in_bits();
    *out_ready = tsi->out_ready();

    return tsi->done() ? (tsi->exit_code() << 1 | 1) : 0;
}
