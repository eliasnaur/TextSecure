package android

import "testing"

func TestCertificates(t *testing.T) {
	var encCert []byte
	encCert = append(encCert, []byte{0x30, 0x82, 0x2, 0xce, 0x30, 0x82, 0x1, 0xb6, 0xa0, 0x3, 0x2, 0x1, 0x2, 0x2, 0x4, 0x58, 0xfe, 0x69, 0x7c, 0x30, 0xd, 0x6, 0x9, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0xd, 0x1, 0x1, 0xb, 0x5, 0x0, 0x30, 0xd, 0x31, 0xb, 0x30, 0x9, 0x6, 0x3, 0x55, 0x4, 0x3, 0x13, 0x2, 0x63, 0x61, 0x30, 0x1e, 0x17, 0xd, 0x31, 0x35, 0x30, 0x34, 0x32, 0x31, 0x32, 0x33, 0x34, 0x36, 0x31, 0x37, 0x5a, 0x17, 0xd, 0x34, 0x32, 0x30, 0x39, 0x30, 0x36, 0x32, 0x33, 0x34, 0x36, 0x31, 0x37, 0x5a, 0x30, 0xd, 0x31, 0xb, 0x30, 0x9, 0x6, 0x3, 0x55, 0x4, 0x3, 0x13, 0x2, 0x63, 0x61, 0x30, 0x82, 0x1, 0x22}...)
	encCert = append(encCert, []byte{0x30, 0xd, 0x6, 0x9, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0xd, 0x1, 0x1, 0x1, 0x5, 0x0, 0x3, 0x82, 0x1, 0xf, 0x0, 0x30, 0x82, 0x1, 0xa, 0x2, 0x82, 0x1, 0x1, 0x0, 0xc6, 0xd4, 0x39, 0xd2, 0xb2, 0x73, 0x30, 0xe, 0x5e, 0xbd, 0x56, 0x64, 0xf7, 0x7e, 0x8d, 0x27, 0x19, 0xea, 0x8e, 0xa8, 0x7d, 0xc9, 0xba, 0x7f, 0x3e, 0x51, 0x2f, 0x73, 0xa5, 0xad, 0xe, 0xaf, 0x38, 0x1f, 0xb7, 0x3b, 0x70, 0xbb, 0x3a, 0x9a, 0x1e, 0x4d, 0x3d, 0x59, 0xbd, 0x13, 0x1, 0x4e, 0x45, 0x13, 0x1a, 0x22, 0x5, 0xd, 0x4, 0x26, 0xa0, 0xd7, 0x7b, 0xbc, 0xbc, 0x97, 0x51, 0x91, 0x65, 0xcf, 0xbd, 0x61, 0xa5, 0xdb, 0xc2}...)
	encCert = append(encCert, []byte{0x8, 0x3, 0xd3, 0x85, 0x14, 0xb8, 0xc1, 0xc7, 0x4c, 0x56, 0x31, 0x93, 0x8a, 0xde, 0x21, 0xc9, 0x39, 0x13, 0x68, 0x99, 0xfb, 0x78, 0x23, 0x5e, 0x23, 0xe3, 0xe0, 0xa5, 0x35, 0x1a, 0xf, 0xa5, 0xb8, 0xd3, 0x69, 0xd2, 0xc, 0x2f, 0x54, 0xe0, 0xc0, 0xe0, 0x7d, 0x2f, 0x44, 0x23, 0xff, 0xf7, 0xe9, 0x6f, 0x43, 0x31, 0xc2, 0x74, 0xf5, 0xda, 0xb8, 0xf8, 0x12, 0x7b, 0x3f, 0xcc, 0x71, 0x1c, 0x2c, 0x1d, 0x50, 0x77, 0xae, 0x8e, 0xb8, 0xf5, 0xbf, 0x48, 0x23, 0xa4, 0xd2, 0x8a, 0x11, 0xb, 0xe7, 0x9, 0x1e, 0xcb, 0x31, 0x3b, 0x1b, 0xaa, 0x1b, 0xe7, 0xa9, 0xc4, 0xa8, 0x64, 0xcd, 0x27, 0x50, 0xf0, 0xe9, 0x92}...)
	encCert = append(encCert, []byte{0xd5, 0xe0, 0xcd, 0xf9, 0xef, 0x5c, 0x76, 0x40, 0xb6, 0x77, 0x88, 0x5e, 0xa5, 0x8a, 0xd4, 0x7e, 0xd1, 0x6e, 0xda, 0x5a, 0xff, 0x2c, 0x62, 0x1a, 0x95, 0x5a, 0x9f, 0x43, 0x92, 0x7c, 0x7e, 0xb6, 0x75, 0x99, 0x9f, 0x25, 0xde, 0xb3, 0xe8, 0xe8, 0x2e, 0x62, 0x4e, 0x34, 0xba, 0x59, 0xd9, 0x78, 0xe5, 0xa3, 0x4e, 0x4d, 0xc2, 0xaf, 0xfd, 0x5f, 0xcf, 0xcd, 0x44, 0x8f, 0xc1, 0x9e, 0xf0, 0x16, 0x35, 0x72, 0x50, 0x56, 0xf7, 0xdf, 0xcd, 0xd7, 0xbf, 0x44, 0x37, 0xe3, 0xd8, 0xc7, 0x2, 0xb8, 0x10, 0x67, 0x87, 0x15, 0xf5, 0x2, 0x3, 0x1, 0x0, 0x1, 0xa3, 0x36, 0x30, 0x34, 0x30, 0x13, 0x6, 0x3, 0x55, 0x1d}...)
	encCert = append(encCert, []byte{0x13, 0x1, 0x1, 0xff, 0x4, 0x9, 0x30, 0x7, 0x1, 0x1, 0xff, 0x2, 0x2, 0x27, 0x10, 0x30, 0x1d, 0x6, 0x3, 0x55, 0x1d, 0xe, 0x4, 0x16, 0x4, 0x14, 0x2e, 0x60, 0x24, 0x4b, 0x3e, 0xbb, 0x50, 0x9b, 0xd, 0xc2, 0x6f, 0x93, 0xf1, 0x2f, 0x1d, 0xdd, 0x7f, 0xed, 0x76, 0x75, 0x30, 0xd, 0x6, 0x9, 0x2a, 0x86, 0x48, 0x86, 0xf7, 0xd, 0x1, 0x1, 0xb, 0x5, 0x0, 0x3, 0x82, 0x1, 0x1, 0x0, 0x50, 0x3c, 0xbc, 0xc1, 0x32, 0x79, 0xc0, 0x97, 0x13, 0x49, 0x18, 0x8b, 0x84, 0x84, 0x6f, 0x9c, 0xe, 0xdc, 0x21, 0x1e, 0x9b, 0x39, 0xf7, 0x14, 0x7d, 0xef, 0x38, 0x92, 0x5a, 0x8b, 0x7a, 0x5e, 0xc1, 0x53}...)
	encCert = append(encCert, []byte{0xfe, 0x91, 0x79, 0x1b, 0x3, 0x2e, 0xa0, 0x91, 0x9e, 0xac, 0x6d, 0x14, 0xc3, 0xab, 0x66, 0x92, 0x1b, 0xda, 0x20, 0xb4, 0x46, 0xac, 0x23, 0x13, 0xed, 0x69, 0xef, 0xa4, 0x45, 0x5, 0x8f, 0x16, 0x1c, 0xb7, 0x95, 0xe4, 0x5e, 0xaf, 0x5f, 0x8e, 0x2b, 0xcd, 0x59, 0x3c, 0xff, 0xd4, 0xa6, 0x3, 0x63, 0x52, 0x8c, 0xef, 0xe9, 0x29, 0x97, 0x9e, 0xee, 0x59, 0x7c, 0x25, 0x31, 0x45, 0x4f, 0xf9, 0x8d, 0xe, 0x9d, 0xc3, 0xd9, 0xc8, 0x30, 0x46, 0x99, 0xb2, 0x5, 0xe5, 0x20, 0xbc, 0xc8, 0x4d, 0xab, 0x77, 0x1a, 0xd3, 0x58, 0xd3, 0x4e, 0x3a, 0x63, 0xa2, 0x7, 0xb2, 0x56, 0xdd, 0x5, 0x2e, 0x9a, 0x7c, 0xf4, 0x2a}...)
	encCert = append(encCert, []byte{0x7e, 0xb4, 0xe4, 0xe8, 0x6f, 0xf7, 0xe, 0x73, 0xf7, 0xc0, 0x84, 0x8d, 0x9e, 0x9b, 0xcc, 0xa7, 0xe6, 0x19, 0x24, 0x63, 0xff, 0xdf, 0xea, 0x59, 0xf, 0x64, 0xf8, 0x8, 0x85, 0xdb, 0xbb, 0xd4, 0xfb, 0x55, 0xfc, 0x4c, 0x20, 0x5b, 0xe1, 0xf4, 0x2c, 0x62, 0x35, 0x4a, 0xb7, 0xc8, 0x1e, 0x50, 0x81, 0x11, 0xbd, 0xea, 0x9d, 0x9c, 0xd9, 0x80, 0x40, 0x4, 0x5f, 0xd2, 0x2a, 0xdd, 0x39, 0x13, 0xb2, 0x7e, 0xa3, 0xa6, 0x32, 0xe2, 0x9c, 0x5b, 0x90, 0xb2, 0xe4, 0xbc, 0x53, 0xe6, 0x1d, 0xa8, 0xd7, 0x90, 0x1e, 0x12, 0xc6, 0xf8, 0x1f, 0x27, 0xcf, 0x2e, 0x87, 0x32, 0xe2, 0x97, 0x9b, 0x6d, 0x4b, 0x40, 0xfc, 0x5d}...)
	encCert = append(encCert, []byte{0x58, 0x32, 0x3, 0xbe, 0xae, 0x11, 0xeb, 0x5e, 0x80, 0x35, 0x88, 0x95, 0xda, 0xe4, 0x41, 0x83, 0xe4, 0x3d, 0x2b, 0x79, 0x68, 0x1}...)
	_, err := decodeCert(encCert)
	if err != nil {
		t.Error(err)
	}
}
