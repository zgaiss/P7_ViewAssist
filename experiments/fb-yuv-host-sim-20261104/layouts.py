"""Real byte-buffer copies, with explicit simulated memory/visibility leases.

No device mappings or target APIs. The conservative layout subset below is a
host-model contract, not a claim that a target framebuffer accepted a format.
"""
from dataclasses import dataclass


class LayoutError(ValueError):
    pass


def align(value, quantum):
    return (value + quantum - 1) // quantum * quantum


@dataclass
class VPUSurface:
    coded_width: int
    coded_height: int
    stride: int
    allocation_height: int
    crop_x: int
    crop_y: int
    width: int
    height: int
    y_offset: int
    u_offset: int
    v_offset: int
    data: bytearray
    seed: int
    leased: bool = True
    cpu_visible: bool = True


def pattern(plane, x, y, seed):
    if plane == 'Y':
        return (3*x + 5*y + seed) & 255
    if plane == 'U':
        return (7*x + 11*y + seed + 61) & 255
    return (13*x + 17*y + seed + 173) & 255


def make_vpu_surface(coded_width, coded_height, seed=1, stride=None,
                     allocation_height=None, crop=None):
    if coded_width <= 0 or coded_height <= 0 or coded_width % 2 or coded_height % 2:
        raise LayoutError('model accepts even, positive planar I420 coded dimensions')
    stride = align(coded_width, 16) if stride is None else stride
    ah = align(coded_height, 16) if allocation_height is None else allocation_height
    if stride < coded_width or stride % 2 or ah < coded_height or ah % 2:
        raise LayoutError('invalid VPU allocation geometry')
    x, y, w, h = crop or (0, 0, coded_width, coded_height)
    y_size, c_size = stride*ah, (stride//2)*(ah//2)
    # Includes a separate MV-sized tail, matching the planning allocation shape.
    raw = bytearray([0xa5]) * (y_size + 3*c_size)
    surface = VPUSurface(coded_width, coded_height, stride, ah, x, y, w, h,
                         0, y_size, y_size+c_size, raw, seed)
    validate_source(surface)
    for plane, offset, pitch, width, height in [
        ('Y', 0, stride, coded_width, coded_height),
        ('U', y_size, stride//2, coded_width//2, coded_height//2),
        ('V', y_size+c_size, stride//2, coded_width//2, coded_height//2)]:
        for row in range(height):
            start = offset + row*pitch
            raw[start:start+width] = bytes(pattern(plane,col,row,seed) for col in range(width))
    return surface


def span(offset, pitch, rows, columns):
    if min(offset, pitch, rows, columns) < 0 or not rows or not columns or pitch < columns:
        raise LayoutError('invalid plane span')
    return offset, offset + (rows-1)*pitch + columns


def validate_source(s):
    fields = [s.coded_width,s.coded_height,s.stride,s.allocation_height,
              s.crop_x,s.crop_y,s.width,s.height,s.y_offset,s.u_offset,s.v_offset]
    if any(not isinstance(v,int) or isinstance(v,bool) for v in fields):
        raise LayoutError('integer metadata required')
    if not s.leased or not s.cpu_visible:
        raise LayoutError('source is released or CPU visibility is unconfirmed')
    if min(s.coded_width,s.coded_height,s.width,s.height) <= 0:
        raise LayoutError('empty source')
    if any(v % 2 for v in [s.coded_width,s.coded_height,s.stride,s.allocation_height,
                          s.crop_x,s.crop_y,s.width,s.height]):
        raise LayoutError('odd 4:2:0 dimension or crop is outside model contract')
    if s.crop_x < 0 or s.crop_y < 0 or s.stride < s.coded_width or s.allocation_height < s.coded_height:
        raise LayoutError('source geometry invalid')
    if s.crop_x+s.width > s.coded_width or s.crop_y+s.height > s.coded_height:
        raise LayoutError('crop extends beyond coded content')
    ranges = []
    for off,pitch,rows,cols in [(s.y_offset,s.stride,s.allocation_height,s.stride),
        (s.u_offset,s.stride//2,s.allocation_height//2,s.stride//2),
        (s.v_offset,s.stride//2,s.allocation_height//2,s.stride//2)]:
        lo,hi = span(off,pitch,rows,cols)
        if hi > len(s.data):
            raise LayoutError('source allocation is shorter than a declared plane')
        if any(lo < old_hi and old_lo < hi for old_lo,old_hi in ranges):
            raise LayoutError('source planes overlap')
        ranges.append((lo,hi))


@dataclass(frozen=True)
class FBLayout:
    width: int
    height: int
    virtual_width: int
    format: str = 'I420'
    slots: int = 3

    def __post_init__(self):
        if self.format not in ('I420','YV12','NV12'):
            raise LayoutError('format is not implemented by this host model')
        if min(self.width,self.height,self.virtual_width,self.slots) <= 0:
            raise LayoutError('empty framebuffer layout')
        if self.width % 2 or self.height % 2 or self.virtual_width % 4:
            raise LayoutError('odd geometry or unaligned stride is conservatively disabled')
        if self.virtual_width < self.width:
            raise LayoutError('framebuffer stride shorter than visible row')
        if any(v % 8 for v in [self.y_bytes,self.y_bytes+self.c_bytes,self.slot_bytes]):
            raise LayoutError('plane/slot offsets violate conservative 8-byte subset')

    @property
    def y_bytes(self): return self.virtual_width*self.height

    @property
    def c_bytes(self): return self.virtual_width//2*(self.height//2)

    @property
    def slot_bytes(self): return self.y_bytes + 2*self.c_bytes

    @property
    def total_bytes(self): return self.slot_bytes*self.slots

    @property
    def fix_line_length(self): return self.virtual_width*12//8

    def offsets(self):
        if self.format == 'I420':
            return {'Y':0,'U':self.y_bytes,'V':self.y_bytes+self.c_bytes}
        if self.format == 'YV12':
            return {'Y':0,'V':self.y_bytes,'U':self.y_bytes+self.c_bytes}
        return {'Y':0,'UV':self.y_bytes}


def copy_to_fb_slot(source, destination, layout, slot, base_address=0x20000000):
    """Validate all metadata, stage a complete copy, then mutate only one slot.

    Caller must separately prove this destination slot is not being scanned.
    Source validity is a supplied model lease; this function performs no fence.
    """
    validate_source(source)
    if (source.width,source.height) != (layout.width,layout.height):
        raise LayoutError('copy is not a scaler')
    if not isinstance(slot,int) or isinstance(slot,bool) or not 0 <= slot < layout.slots:
        raise LayoutError('invalid slot')
    begin = slot*layout.slot_bytes
    end = begin+layout.slot_bytes
    if end > len(destination) or base_address < 0 or (base_address+begin) % 8:
        raise LayoutError('destination span or alignment invalid')
    staged = bytearray(layout.slot_bytes)
    # Deterministic padding values; they are not a claim about display black/range.
    staged[layout.y_bytes:] = bytes([128])*(2*layout.c_bytes)
    for row in range(layout.height):
        start = source.y_offset+(source.crop_y+row)*source.stride+source.crop_x
        dst = row*layout.virtual_width
        staged[dst:dst+layout.width] = source.data[start:start+layout.width]
    chroma_pitch = source.stride//2
    offsets = layout.offsets()
    for row in range(layout.height//2):
        starts = [off+(source.crop_y//2+row)*chroma_pitch+source.crop_x//2
                  for off in [source.u_offset,source.v_offset]]
        u,v = [source.data[a:a+layout.width//2] for a in starts]
        if layout.format == 'NV12':
            dst = layout.y_bytes+row*layout.virtual_width
            staged[dst:dst+layout.width:2] = u
            staged[dst+1:dst+layout.width:2] = v
        else:
            for tag,data in [('U',u),('V',v)]:
                dst = offsets[tag]+row*(layout.virtual_width//2)
                staged[dst:dst+layout.width//2] = data
    destination[begin:end] = staged
    return {'slot':slot,'slot_bytes':layout.slot_bytes,'copied_visible_bytes':layout.width*layout.height*3//2,
            'source_y_stride':source.stride,'source_allocation_height':source.allocation_height,
            'destination_y_stride':layout.virtual_width,'format':layout.format}


def sample_fb(data, layout, slot, plane, x, y):
    """Pixel-oriented oracle, independent of the row-copy operation."""
    base=slot*layout.slot_bytes
    if plane == 'Y': return data[base+y*layout.virtual_width+x]
    offsets=layout.offsets()
    if layout.format == 'NV12':
        return data[base+layout.y_bytes+y*layout.virtual_width+2*x+(plane=='V')]
    return data[base+offsets[plane]+y*(layout.virtual_width//2)+x]
