//go:build tools

package ptcore

// gomobile bind 要求模块依赖里有 golang.org/x/mobile/bind；这里占住它，免得 go mod tidy 把它删掉。
import _ "golang.org/x/mobile/bind"
