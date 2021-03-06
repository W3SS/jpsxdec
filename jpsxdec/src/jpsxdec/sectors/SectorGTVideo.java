/*
 * jPSXdec: PlayStation 1 Media Decoder/Converter in Java
 * Copyright (C) 2015-2017  Michael Sabin
 * All rights reserved.
 *
 * Redistribution and use of the jPSXdec code or any derivative works are
 * permitted provided that the following conditions are met:
 *
 *  * Redistributions may not be sold, nor may they be used in commercial
 *    or revenue-generating business activities.
 *
 *  * Redistributions that are modified from the original source must
 *    include the complete source code, including the source code for all
 *    components used by a binary built from the modified sources. However, as
 *    a special exception, the source code distributed need not include
 *    anything that is normally distributed (in either source or binary form)
 *    with the major components (compiler, kernel, and so on) of the operating
 *    system on which the executable runs, unless that component itself
 *    accompanies the executable.
 *
 *  * Redistributions must reproduce the above copyright notice, this list
 *    of conditions and the following disclaimer in the documentation and/or
 *    other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package jpsxdec.sectors;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import jpsxdec.cdreaders.CdSector;
import jpsxdec.cdreaders.CdxaSubHeader.SubMode;
import jpsxdec.i18n.I;
import jpsxdec.psxvideo.bitstreams.BitStreamUncompressor_Iki;
import jpsxdec.psxvideo.mdec.MdecException;
import jpsxdec.util.BinaryDataNotRecognized;
import jpsxdec.util.IO;
import jpsxdec.util.LocalizedIncompatibleException;


/** Represents a Gran Turismo 1 and 2 video sector. */
public class SectorGTVideo extends SectorAbstractVideo 
        implements IVideoSectorWithFrameNumber
{
    
    // .. Static stuff .....................................................

    private static final long GT_MAGIC = 0x53490160;

    // .. Fields ..........................................................

    private final CommonVideoSectorFirst16bytes _header = new CommonVideoSectorFirst16bytes();
    private int  _iTotalFrames;         //  16   [2 bytes]
    /** seems 0x8000 for first chunk and 0x4000 for last chunk (0xc000 for both). */
    private int  _iChunkBitFlags;       //  18   [2 bytes]
    // 12 zeroes                        //  20   [12 bytes]
    //   32 TOTAL

    /** Populated either by the first sector's iki frame header,
     * or by the first chunk of the frame this chunk belongs to. */
    private int _iWidth, _iHeight;

    @Override
    final protected int getSectorHeaderSize() { return 32; }

    /** Gets dimensions from sector data if chunk = 0,
     * otherwise needs prevChunk0 for dimensions. 
     * @param prevChunk0 Previous chunk = 0. */
    public SectorGTVideo(@Nonnull CdSector cdSector, @CheckForNull SectorGTVideo prevChunk0) {
        super(cdSector);
        if (isSuperInvalidElseReset()) return;

        // only if it has a sector header should we check if it reports DATA
        if (cdSector.hasSubHeader() &&
            cdSector.subModeMask(SubMode.MASK_DATA) == 0)
        {
            return;
        }

        _header.readMagic(cdSector);
        if (_header.lngMagic != GT_MAGIC)
            return;

        if (_header.readChunkNumberStandard(cdSector))
            return;
        if (_header.readChunksInFrameStandard(cdSector))
            return;
        _header.readFrameNumber(cdSector);
        if (_header.iFrameNumber < 1)
            return;
        if (_header.readUsedDemuxSizeStandard(cdSector))
            return;
        _iTotalFrames = cdSector.readSInt16LE(16);
        if (_iTotalFrames < 1)
            return;
        _iChunkBitFlags = cdSector.readUInt16LE(18);
        if ((_iChunkBitFlags & ~0xc000) != 0)
            return;

        int iZero1 = cdSector.readSInt32LE(20);
        int iZero2 = cdSector.readSInt32LE(24);
        int iZero3 = cdSector.readSInt32LE(28);
        if (iZero1 != 0 || iZero2 != 0 || iZero3 != 0)
            return;

        if (_header.iChunkNumber == 0) {
            if (cdSector.getCdUserDataSize() < getSectorHeaderSize()+10)
                return;

            int _iMdecCodeCount = cdSector.readUInt16LE(getSectorHeaderSize()+0);
            int iMagic3800 = cdSector.readUInt16LE(getSectorHeaderSize()+2);
            _iWidth = cdSector.readUInt16LE(getSectorHeaderSize()+4);
            _iHeight = cdSector.readUInt16LE(getSectorHeaderSize()+6);
            int _iCompressedDataSize = cdSector.readUInt16LE(getSectorHeaderSize()+8);

            if (_iMdecCodeCount < 0 || iMagic3800 != 0x3800 || _iWidth < 1 || _iHeight < 1 || _iCompressedDataSize < 1)
                return;
        } else if (prevChunk0 == null || prevChunk0.getProbability() == 0) {
            return;
        } else {
            if (_header.iFrameNumber != prevChunk0._header.iFrameNumber ||
                _header.iChunksInThisFrame != prevChunk0._header.iChunksInThisFrame ||
                _iTotalFrames != prevChunk0._iTotalFrames ||
                _header.iChunkNumber <= prevChunk0._header.iChunkNumber)
            {
                return;
            }

            _iWidth = prevChunk0._iWidth;
            _iHeight = prevChunk0._iHeight;
        }

        setProbability(100);
    }

    // .. Public methods ...................................................

    public @Nonnull String getTypeName() {
        return "GT Video";
    }

    public String toString() {
        return String.format("%s %s frame:%d/%d chunk:%d/%d %dx%d flags:%04x {demux frame size=%d}",
            getTypeName(),
            super.cdToString(),
            _header.iFrameNumber,
            _iTotalFrames,
            _header.iChunkNumber,
            _header.iChunksInThisFrame,
            _iWidth, _iHeight,
            _iChunkBitFlags,
            _header.iUsedDemuxedSize
            );
    }

    public int getChunkNumber() {
        return _header.iChunkNumber;
    }

    public int getChunksInFrame() {
        return _header.iChunksInThisFrame;
    }

    public int getFrameNumber() {
        return _header.iFrameNumber;
    }

    public int getWidth() {
        return _iWidth;
    }

    public int getHeight() {
        return _iHeight;
    }

    @Override
    public int checkAndPrepBitstreamForReplace(@Nonnull byte[] abDemuxData, int iUsedSize,
                                               int iMdecCodeCount, @Nonnull byte[] abSectUserData)
            throws LocalizedIncompatibleException
    {
        final int[] aiDimensions;
        try {
            aiDimensions = BitStreamUncompressor_Iki.getDimensions(abDemuxData);
        } catch (BinaryDataNotRecognized ex) {
            throw new LocalizedIncompatibleException(I.REPLACE_FRAME_TYPE_NOT_IKI(), ex);
        }
        int iWidth = aiDimensions[0];
        int iHeight = aiDimensions[1];
        if (iWidth != _iWidth || iHeight != _iHeight) {
            throw new LocalizedIncompatibleException(I.REPLACE_FRAME_IKI_DIMENSIONS_MISMATCH(
                                            iWidth, iHeight, _iWidth, _iHeight));
        }

        int iDemuxSizeForHeader = (iUsedSize + 3) & ~3;

        IO.writeInt32LE(abSectUserData, 12, iDemuxSizeForHeader);

        return getSectorHeaderSize();
    }

}

