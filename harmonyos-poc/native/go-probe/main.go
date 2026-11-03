package main

/*
#include <stdint.h>
#include <stdlib.h>
#include <unistd.h>
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

//export ZJUProbeFdValid
func ZJUProbeFdValid(fd C.int32_t) C.int32_t {
	if fd < 0 {
		return 0
	}
	duplicate := C.dup(C.int(fd))
	if duplicate < 0 {
		return 0
	}
	C.close(duplicate)
	return 1
}

//export ZJUProbeFree
func ZJUProbeFree(value *C.char) {
	C.free(unsafe.Pointer(value))
}
