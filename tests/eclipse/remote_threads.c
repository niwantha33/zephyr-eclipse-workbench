// SPDX-License-Identifier: Apache-2.0
#define _GNU_SOURCE
#include <pthread.h>
#include <unistd.h>
#include <sys/prctl.h>
#include <signal.h>

static void *worker(void *name)
{
    pthread_setname_np(pthread_self(), name);
    for (;;) usleep(10000);
    return 0;
}
__attribute__((noinline)) void zew_first_stop(void) { __asm__ volatile(""); }
__attribute__((noinline)) void zew_second_stop(void) { __asm__ volatile(""); }
int main(void)
{
    pthread_t first, second;
    prctl(PR_SET_PDEATHSIG, SIGKILL);
    zew_first_stop();
    usleep(400000);
    pthread_create(&first, 0, worker, "zew-worker-a");
    pthread_create(&second, 0, worker, "zew-worker-b");
    usleep(50000);
    zew_second_stop();
    pthread_join(first, 0);
    return 0;
}
