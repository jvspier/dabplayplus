#!/bin/bash
# Test vectors for the Kotlin MP2 (MPEG-1/2 Layer II) decoder: synthetic signals only (tones + noise, no recordings).
# Each case: <name>.mp2 (the encoded stream) and <name>.s16 (ffmpeg's decoded reference, 16-bit LE, interleaved).
# Covers what DAB uses: 48 kHz (allocation tables B.2a and B.2c), 24 kHz half rate (LSF table), mono / stereo /
# joint stereo, and CRC-protected frames (DAB always sends the header CRC).
set -euo pipefail
out=${1:-android/dab-core/src/test/resources/mp2}
mkdir -p "$out"
sig() {  # $1 = sample rate, $2 = channels
  if [ "$2" = 1 ]; then echo "sine=f=440:r=$1:d=0.5,volume=0.5[a];anoisesrc=r=$1:d=0.5:a=0.08:c=pink[n];[a][n]amix=inputs=2:normalize=0"
  else echo "sine=f=440:r=$1:d=0.5[l];sine=f=663:r=$1:d=0.5[r];[l][r]amerge,volume=0.5[a];anoisesrc=r=$1:d=0.5:a=0.08:c=pink,pan=stereo|c0=c0|c1=0.3*c0[n];[a][n]amix=inputs=2:normalize=0"; fi
}
enc() {  # name rate channels bitrate codec [extra ffmpeg options]
  local name=$1 rate=$2 ch=$3 br=$4 codec=$5; shift 5
  ffmpeg -hide_banner -loglevel error -y -filter_complex "$(sig "$rate" "$ch")" -ac "$ch" -ar "$rate" -c:a "$codec" -b:a "${br}k" "$@" -f mp2 "$out/$name.mp2"
  ffmpeg -hide_banner -loglevel error -y -c:a mp2float -i "$out/$name.mp2" -f s16le -acodec pcm_s16le "$out/$name.s16"
}
enc s48_192k   48000 2 192 mp2                                   # table B.2a (27 subbands), stereo
enc m48_64k    48000 1 64  mp2                                   # table B.2a, mono
enc s48_64k    48000 2 64  mp2                                   # table B.2c (8 subbands): 32 kbit/s per channel
enc s24_64k    24000 2 64  mp2                                   # MPEG-2 half rate (LSF table, 30 subbands)
enc m24_32k    24000 1 32  mp2                                   # half rate, mono
enc j48_128k   48000 2 128 libtwolame -mode joint_stereo         # joint (intensity) stereo
enc c48_160k   48000 2 160 libtwolame -error_protection 1        # with header CRC, like DAB
ls -la "$out"
