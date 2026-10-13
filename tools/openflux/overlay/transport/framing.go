// SPDX-License-Identifier: GPL-3.0-or-later
package transport

import (
	"encoding/binary"
	"errors"
	"fmt"

	"github.com/klauspost/compress/zstd"
)

// Batch v2: version, flags, then optional zstd over uint16-BE length records.
const (
	batchFormatVersion = 0x02
	batchFlagZstd      = 0x01
	maxFrameBytes      = 1 << 20
	maxFrameRecords    = 1024
)

var (
	zstdEnc *zstd.Encoder
	zstdDec *zstd.Decoder
)

func init() {
	var err error
	zstdEnc, err = zstd.NewWriter(nil, zstd.WithEncoderLevel(zstd.SpeedDefault),
		zstd.WithEncoderConcurrency(1), zstd.WithWindowSize(maxFrameBytes))
	if err != nil {
		panic(fmt.Sprintf("zstd encoder init: %v", err))
	}
	zstdDec, err = zstd.NewReader(nil, zstd.WithDecoderConcurrency(1),
		zstd.WithDecoderMaxMemory(maxFrameBytes), zstd.WithDecoderMaxWindow(maxFrameBytes))
	if err != nil {
		panic(fmt.Sprintf("zstd decoder init: %v", err))
	}
}

func frameBatch(packets [][]byte) []byte {
	if len(packets) > maxFrameRecords {
		return nil
	}
	total := 0
	for _, packet := range packets {
		if len(packet) > 65535 || total+2+len(packet) > maxFrameBytes {
			return nil
		}
		total += 2 + len(packet)
	}
	framed := make([]byte, 0, total)
	for _, packet := range packets {
		framed = binary.BigEndian.AppendUint16(framed, uint16(len(packet)))
		framed = append(framed, packet...)
	}
	return framed
}

func encodeBatch(packets [][]byte) []byte {
	framed := frameBatch(packets)
	if framed == nil {
		return nil
	}
	compressed := zstdEnc.EncodeAll(framed, nil)
	if len(compressed) < len(framed) {
		return append([]byte{batchFormatVersion, batchFlagZstd}, compressed...)
	}
	return append([]byte{batchFormatVersion, 0}, framed...)
}

func decodeBatch(data []byte) ([][]byte, error) {
	if len(data) < 2 || len(data) > maxFrameBytes+2 {
		return nil, errors.New("invalid batch frame size")
	}
	if data[0] != batchFormatVersion || data[1]&^byte(batchFlagZstd) != 0 {
		return nil, errors.New("unsupported batch frame version or flags")
	}
	framed := data[2:]
	if data[1]&batchFlagZstd != 0 {
		var err error
		framed, err = zstdDec.DecodeAll(framed, nil)
		if err != nil {
			return nil, errors.New("invalid compressed batch frame")
		}
	}
	if len(framed) > maxFrameBytes {
		return nil, errors.New("decoded batch exceeds size limit")
	}
	var packets [][]byte
	for len(framed) > 0 {
		if len(packets) >= maxFrameRecords || len(framed) < 2 {
			return nil, errors.New("invalid batch record count or length")
		}
		n := int(binary.BigEndian.Uint16(framed[:2]))
		framed = framed[2:]
		if len(framed) < n {
			return nil, errors.New("truncated batch record")
		}
		packet := append([]byte{}, framed[:n]...)
		packets = append(packets, packet)
		framed = framed[n:]
	}
	return packets, nil
}
