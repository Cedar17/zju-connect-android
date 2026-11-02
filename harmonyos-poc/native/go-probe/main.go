package main

/*
#include <stdint.h>
#include <stdlib.h>
*/
import "C"

import (
	"fmt"
	"runtime"
	"unsafe"
)

func main() {}

//export ZJUProbeVersion
func ZJUProbeVersion() *C.char {
	return C.CString(fmt.Sprintf("%s (%s/%s)", runtime.Version(), runtime.GOOS, runtime.GOARCH))
}

//export ZJUProbeAdd
func ZJUProbeAdd(a, b C.int32_t) C.int32_t {
	return a + b
}

//export ZJUProbeFree
func ZJUProbeFree(value *C.char) {
	C.free(unsafe.Pointer(value))
}
