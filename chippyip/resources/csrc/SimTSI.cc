#include <vpi_user.h>
#include <svdpi.h>
#include <map>
#include <string>
#include "testchip_tsi.h"

int next_id = 0;
std::map<int, testchip_tsi_t*> tsis;

extern "C" int tsi_init(int argc, svOpenArrayHandle argv, unsigned char can_have_loadmem)
{
    int id = next_id++;
    tsis[id] = new testchip_tsi_t(argc, (char**)svGetArrElemPtr1(argv, 0), can_have_loadmem);
    return id;
}

extern "C" int tsi_tick(
                        int id,
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

    testchip_tsi_t* tsi = tsis[id];
    
    tsi->tick(out_valid, out_bits, in_ready);
    tsi->switch_to_host();

    *in_valid = tsi->in_valid();
    *in_bits = tsi->in_bits();
    *out_ready = tsi->out_ready();

    return tsi->done() ? (tsi->exit_code() << 1 | 1) : 0;
}
